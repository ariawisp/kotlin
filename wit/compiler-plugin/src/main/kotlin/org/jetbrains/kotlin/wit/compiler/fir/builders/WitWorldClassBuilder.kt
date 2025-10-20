package org.jetbrains.kotlin.wit.compiler.fir

import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtLightSourceElement
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.com.intellij.lang.LighterASTNode
import org.jetbrains.kotlin.com.intellij.openapi.util.Ref
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.com.intellij.util.diff.FlyweightCapableTreeStructure
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fakeElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.plugin.DeclarationBuildingContext
import org.jetbrains.kotlin.fir.plugin.createCompanionObject
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.plugin.createMemberProperty
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.plugin.createTopLevelClass
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeAliasSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.types.withNullability
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.wit.compiler.WIT_DRIVER_BIND_FUNCTION_NAME
import org.jetbrains.kotlin.wit.compiler.WIT_DRIVER_OBJECT_SIMPLE_NAME

internal class WitWorldClassBuilder(
    private val extension: FirDeclarationGenerationExtension,
    private val session: FirSession,
    private val annotationBuilder: WitFirAnnotationBuilder,
    private val classSourceCache: MutableMap<ClassId, KtSourceElement>,
) {

    fun generateTopLevelClass(
        classId: ClassId,
        metadata: WorldMetadata,
    ): FirClassLikeSymbol<*> {
        val worldSource = ensureWorldSource(classId, metadata)
        val classSource = worldSource
        val worldClass = extension.createTopLevelClass(classId, WitWorldDeclarationKey) {
            source = classSource
            modality = Modality.ABSTRACT
        }.apply {
            annotationBuilder.buildWorldAnnotation(metadata)?.let { annotation ->
                replaceAnnotations(annotations + annotation)
            }
        }
        recordClassSource(classId, worldClass.source ?: classSource)
        worldClass.witWorldMetadata = metadata
        return worldClass.symbol
    }

    @OptIn(SymbolInternals::class)
    fun getNestedClassifiersNames(
        classSymbol: FirClassSymbol<*>,
        metadata: WorldMetadata,
        pluginOrigin: FirDeclarationOrigin.Plugin,
    ): Set<Name> {
        return when (pluginOrigin.key) {
            WitWorldDeclarationKey -> setOf(SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT)
            WitWorldDriverCompanionKey -> setOf(DRIVER_OBJECT_NAME)
            WitWorldDriverClassKey -> buildSet {
                metadata.driverImportsClassId?.let { add(IMPORTS_INTERFACE_NAME) }
                metadata.driverExportsClassId?.let { add(EXPORTS_INTERFACE_NAME) }
                metadata.driverResourcesClassId?.let { add(RESOURCES_INTERFACE_NAME) }
            }
            else -> emptySet()
        }
    }

    fun generateNestedClassLikeDeclaration(
        owner: FirClassSymbol<*>,
        name: Name,
        metadata: WorldMetadata,
        pluginOrigin: FirDeclarationOrigin.Plugin,
    ): FirClassLikeSymbol<*>? {
        return when (pluginOrigin.key) {
            WitWorldDeclarationKey -> generateCompanion(owner, name, metadata)
            WitWorldDriverCompanionKey -> generateDriverClass(owner, name, metadata)
            WitWorldDriverClassKey -> generateDriverNestedInterface(owner, name, metadata)
            else -> null
        }
    }

    fun getCallableNamesForClass(
        pluginKey: org.jetbrains.kotlin.GeneratedDeclarationKey,
        metadata: WorldMetadata,
    ): Set<Name> {
        return when (pluginKey) {
            WitWorldDeclarationKey -> buildSet {
                add(WORLD_NAME_PROPERTY_NAME)
                addAll(metadata.interfaceBindings.keys)
                addAll(metadata.importBindings.keys)
                addAll(metadata.exportBindings.keys)
                addAll(metadata.resourceBindings.keys)
                metadata.importBindings.values.mapNotNullTo(this) { it.functionStubName }
                metadata.exportBindings.values.mapNotNullTo(this) { it.functionStubName }
            }
            WitWorldDriverCompanionKey -> buildSet {
                add(RUNTIME_SLOT_PROPERTY_NAME)
                add(BIND_FUNCTION_NAME)
                metadata.registerImportsCallableId?.let { add(REGISTER_IMPORTS_FUNCTION_NAME) }
                metadata.registerExportsCallableId?.let { add(REGISTER_EXPORTS_FUNCTION_NAME) }
                metadata.registerResourcesCallableId?.let { add(REGISTER_RESOURCES_FUNCTION_NAME) }
                addAll(metadata.constructorHelpers.keys)
            }
            WitWorldDriverImportsKey -> metadata.importBindings.values.mapNotNullTo(mutableSetOf()) { it.hostFunctionName }
            WitWorldDriverExportsKey -> metadata.exportBindings.values.mapNotNullTo(mutableSetOf()) { it.hostFunctionName }
            WitWorldDriverResourcesKey -> metadata.resourceBindings.values.mapTo(mutableSetOf()) { it.hostFunctionName }
            else -> emptySet()
        }
    }

    fun generateWorldProperties(
        owner: FirRegularClassSymbol,
        metadata: WorldMetadata,
        callableName: Name,
    ): List<FirPropertySymbol> {
        if (callableName == WORLD_NAME_PROPERTY_NAME) {
            val property = extension.createMemberProperty(
                owner,
                WitWorldDeclarationKey,
                WORLD_NAME_PROPERTY_NAME,
                session.builtinTypes.stringType.coneType,
                isVal = true,
                hasBackingField = false,
            ) {
                setMemberSource(owner)
                visibility = Visibilities.Public
            }
            property.witWorldMetadata = metadata
            return listOf(property.symbol)
        }

        metadata.interfaceBindings[callableName]?.let { interfaceMetadata ->
            val property = extension.createMemberProperty(
                owner,
                WitWorldDeclarationKey,
                callableName,
                session.builtinTypes.stringType.coneType,
                isVal = true,
                hasBackingField = false,
            ) {
                setMemberSource(owner)
                visibility = Visibilities.Public
            }
            property.witWorldMetadata = metadata
            property.witInterfaceMetadata = interfaceMetadata.schema
            property.witRuntimeInterface = interfaceMetadata.runtime
            return listOf(property.symbol)
        }

        val bindingMetadata = metadata.importBindings[callableName] ?: metadata.exportBindings[callableName]
        if (bindingMetadata != null) {
            val property = extension.createMemberProperty(
                owner,
                WitWorldDeclarationKey,
                callableName,
                session.builtinTypes.anyType.coneType,
                isVal = true,
                hasBackingField = false,
            ) {
                setMemberSource(owner)
                visibility = Visibilities.Public
            }
            property.witWorldMetadata = metadata
            property.witBindingMetadata = bindingMetadata
        annotationBuilder.buildBindingAnnotation(bindingMetadata)?.let { annotation ->
            property.replaceAnnotations(property.annotations + annotation)
        }
            return listOf(property.symbol)
        }

        metadata.resourceBindings[callableName]?.let { resourceMetadata ->
            val property = extension.createMemberProperty(
                owner,
                WitWorldDeclarationKey,
                callableName,
                session.builtinTypes.anyType.coneType,
                isVal = true,
                hasBackingField = false,
            ) {
                setMemberSource(owner)
                visibility = Visibilities.Public
            }
            property.witWorldMetadata = metadata
            property.witResourceMetadata = resourceMetadata
        annotationBuilder.buildResourceAnnotation(resourceMetadata)?.let { annotation ->
            property.replaceAnnotations(property.annotations + annotation)
        }
            return listOf(property.symbol)
        }

        return emptyList()
    }

    fun generateCompanionProperties(
        owner: FirRegularClassSymbol,
        metadata: WorldMetadata,
        callableName: Name,
    ): List<FirPropertySymbol> {
        if (callableName != RUNTIME_SLOT_PROPERTY_NAME) return emptyList()
        val componentRuntimeSymbol =
            session.symbolProvider.getClassLikeSymbolByClassId(COMPONENT_RUNTIME_CLASS_ID) as? FirClassSymbol<*>
        val propertyType = componentRuntimeSymbol?.defaultType()?.withNullability(nullable = true, session.typeContext)
            ?: session.builtinTypes.anyType.coneType.withNullability(nullable = true, session.typeContext)
        val property = extension.createMemberProperty(
            owner,
            WitWorldDriverCompanionKey,
            callableName,
            propertyType,
            isVal = false,
            hasBackingField = true,
        ) {
            setMemberSource(owner)
            visibility = Visibilities.Private
            modality = Modality.FINAL
        }
        property.witWorldMetadata = metadata
        property.replaceInitializer(buildNullLiteral())
        return listOf(property.symbol)
    }

    fun configureDriverObject(
        driverClass: FirRegularClass,
        metadata: WorldMetadata,
    ) {
        driverClass.witWorldMetadata = metadata
        val driverClassSymbol = driverClass.symbol
        val componentRuntimeSymbol =
            session.symbolProvider.getClassLikeSymbolByClassId(COMPONENT_RUNTIME_CLASS_ID) as? FirClassSymbol<*>
                ?: return
        val bindingHandlerType = resolveBindingHandlerType()

        val packageProperty = extension.createMemberProperty(
            driverClassSymbol,
            WitWorldDriverClassKey,
            DRIVER_PACKAGE_ID_PROPERTY_NAME,
            session.builtinTypes.stringType.coneType,
            isVal = true,
            hasBackingField = true,
        ) {
            setMemberSource(driverClassSymbol)
            modality = Modality.FINAL
            visibility = Visibilities.Public
            status { isOverride = true }
        }
        packageProperty.replaceInitializer(buildStringLiteral(metadata.packageId))
        packageProperty.witWorldMetadata = metadata

        val worldNameProperty = extension.createMemberProperty(
            driverClassSymbol,
            WitWorldDriverClassKey,
            DRIVER_WORLD_NAME_PROPERTY_NAME,
            session.builtinTypes.stringType.coneType,
            isVal = true,
            hasBackingField = true,
        ) {
            setMemberSource(driverClassSymbol)
            modality = Modality.FINAL
            visibility = Visibilities.Public
            status { isOverride = true }
        }
        worldNameProperty.replaceInitializer(buildStringLiteral(metadata.runtimeWorld.name))
        worldNameProperty.witWorldMetadata = metadata

        val driverBindFunction = extension.createMemberFunction(
            driverClassSymbol,
            WitWorldDriverClassKey,
            BIND_FUNCTION_NAME,
            session.builtinTypes.unitType.coneType,
        ) {
            setMemberSource(driverClassSymbol)
            modality = Modality.FINAL
            visibility = Visibilities.Public
            status { isOverride = true }
            valueParameter(RUNTIME_PARAMETER_NAME, componentRuntimeSymbol.defaultType())
        }
        driverBindFunction.witWorldFunctionMetadata = metadata

        val registerImportFunction = extension.createMemberFunction(
            driverClassSymbol,
            WitWorldDriverClassKey,
            REGISTER_IMPORT_HANDLER_NAME,
            session.builtinTypes.unitType.coneType,
        ) {
            setMemberSource(driverClassSymbol)
            modality = Modality.FINAL
            visibility = Visibilities.Public
            status { isOverride = false }
            valueParameter(RUNTIME_PARAMETER_NAME, componentRuntimeSymbol.defaultType())
            valueParameter(Name.identifier("bindingName"), session.builtinTypes.stringType.coneType)
            valueParameter(Name.identifier("handler"), bindingHandlerType)
        }
        registerImportFunction.witWorldFunctionMetadata = metadata

        val registerExportFunction = extension.createMemberFunction(
            driverClassSymbol,
            WitWorldDriverClassKey,
            REGISTER_EXPORT_HANDLER_NAME,
            session.builtinTypes.unitType.coneType,
        ) {
            setMemberSource(driverClassSymbol)
            modality = Modality.FINAL
            visibility = Visibilities.Public
            status { isOverride = false }
            valueParameter(RUNTIME_PARAMETER_NAME, componentRuntimeSymbol.defaultType())
            valueParameter(Name.identifier("bindingName"), session.builtinTypes.stringType.coneType)
            valueParameter(Name.identifier("handler"), bindingHandlerType)
        }
        registerExportFunction.witWorldFunctionMetadata = metadata
    }

    fun ensureWorldSource(
        classId: ClassId,
        metadata: WorldMetadata,
    ): KtSourceElement {
        val key = classId.outermostClassId ?: classId
        return classSourceCache[key] ?: createWorldSyntheticSource(metadata).also { synthetic ->
            recordClassSource(key, synthetic)
        }
    }

    fun recordClassSource(classId: ClassId, source: KtSourceElement?) {
        if (source != null) {
            classSourceCache[classId] = source
        }
    }

    fun DeclarationBuildingContext<*>.setMemberSource(ownerSymbol: FirClassSymbol<*>) {
        val ownerSource = classSourceCache[ownerSymbol.classId]
        source = ownerSource?.fakeElement(KtFakeSourceElementKind.PluginGenerated)
    }

    fun applyMemberSource(
        context: DeclarationBuildingContext<*>,
        ownerSymbol: FirClassSymbol<*>,
    ) {
        context.setMemberSource(ownerSymbol)
    }

    private fun generateCompanion(
        owner: FirClassSymbol<*>,
        name: Name,
        metadata: WorldMetadata,
    ): FirClassLikeSymbol<*>? {
        if (name != SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT) return null
        val companionSource = classSourceCache[owner.classId] ?: owner.source
        val companion = extension.createCompanionObject(owner, WitWorldDriverCompanionKey) {
            source = companionSource
        }
        recordClassSource(companion.symbol.classId, companion.source ?: companionSource)
        companion.witWorldMetadata = metadata
        return companion.symbol
    }

    private fun generateDriverClass(
        owner: FirClassSymbol<*>,
        name: Name,
        metadata: WorldMetadata,
    ): FirClassLikeSymbol<*>? {
        if (name != DRIVER_OBJECT_NAME) return null
        val driverSuperType =
            session.symbolProvider.getClassLikeSymbolByClassId(WORLD_DRIVER_CLASS_ID)?.defaultType()
                ?: return null
        val driverSource = classSourceCache[owner.classId]?.fakeElement(KtFakeSourceElementKind.PluginGenerated)
        val driverClass = extension.createNestedClass(
            owner,
            DRIVER_OBJECT_NAME,
            WitWorldDriverClassKey,
            classKind = ClassKind.OBJECT,
        ) {
            source = driverSource
            superType { _ -> driverSuperType }
            modality = Modality.FINAL
        }
        recordClassSource(driverClass.symbol.classId, driverClass.source ?: driverSource)
        configureDriverObject(driverClass, metadata)
        return driverClass.symbol
    }

    private fun generateDriverNestedInterface(
        owner: FirClassSymbol<*>,
        name: Name,
        metadata: WorldMetadata,
    ): FirClassLikeSymbol<*>? {
        return when (name) {
            IMPORTS_INTERFACE_NAME -> generateDriverContractInterface(owner, metadata, BindingDirection.IMPORT)
            EXPORTS_INTERFACE_NAME -> generateDriverContractInterface(owner, metadata, BindingDirection.EXPORT)
            RESOURCES_INTERFACE_NAME -> generateDriverResourcesInterface(owner, metadata)
            else -> null
        }
    }

    private fun generateDriverContractInterface(
        owner: FirClassSymbol<*>,
        metadata: WorldMetadata,
        direction: BindingDirection,
    ): FirClassLikeSymbol<*>? {
        val contractClassId = when (direction) {
            BindingDirection.IMPORT -> metadata.driverImportsClassId
            BindingDirection.EXPORT -> metadata.driverExportsClassId
        } ?: return null
        val expectedName = when (direction) {
            BindingDirection.IMPORT -> IMPORTS_INTERFACE_NAME
            BindingDirection.EXPORT -> EXPORTS_INTERFACE_NAME
        }
        val classSource = classSourceCache[owner.classId]?.fakeElement(KtFakeSourceElementKind.PluginGenerated)
            ?: owner.source?.fakeElement(KtFakeSourceElementKind.PluginGenerated)
        val key = when (direction) {
            BindingDirection.IMPORT -> WitWorldDriverImportsKey
            BindingDirection.EXPORT -> WitWorldDriverExportsKey
        }
        val interfaceClass = extension.createNestedClass(
            owner,
            expectedName,
            key,
            classKind = ClassKind.INTERFACE,
        ) {
            source = classSource
            modality = Modality.ABSTRACT
            visibility = Visibilities.Public
        }
        recordClassSource(contractClassId, interfaceClass.source ?: classSource)
        interfaceClass.witWorldMetadata = metadata
        return interfaceClass.symbol
    }

    private fun generateDriverResourcesInterface(
        owner: FirClassSymbol<*>,
        metadata: WorldMetadata,
    ): FirClassLikeSymbol<*>? {
        val contractClassId = metadata.driverResourcesClassId ?: return null
        val classSource = classSourceCache[owner.classId]?.fakeElement(KtFakeSourceElementKind.PluginGenerated)
            ?: owner.source?.fakeElement(KtFakeSourceElementKind.PluginGenerated)
        val interfaceClass = extension.createNestedClass(
            owner,
            RESOURCES_INTERFACE_NAME,
            WitWorldDriverResourcesKey,
            classKind = ClassKind.INTERFACE,
        ) {
            source = classSource
            modality = Modality.ABSTRACT
            visibility = Visibilities.Public
        }
        recordClassSource(contractClassId, interfaceClass.source ?: classSource)
        interfaceClass.witWorldMetadata = metadata
        return interfaceClass.symbol
    }

    private fun createWorldSyntheticSource(metadata: WorldMetadata): KtLightSourceElement {
        val filePath = buildSyntheticFilePath(metadata)
        val descriptor = "${metadata.packageId}/${metadata.runtimeWorld.name}"
        val debugText = "synthetic WIT declaration: $descriptor"
        val endOffset = debugText.length.coerceAtLeast(1)
        val node = SyntheticLightNode(
            elementType = org.jetbrains.kotlin.KtNodeTypes.CLASS,
            start = 0,
            end = endOffset,
            debugText = debugText,
        )
        val tree = SingleNodeTreeStructure(node, filePath)
        return KtLightSourceElement(node, 0, endOffset, tree, KtFakeSourceElementKind.PluginGenerated)
    }

    private fun buildSyntheticFilePath(metadata: WorldMetadata): String {
        val withoutVersion = metadata.packageId.substringBefore('@')
        val segments = withoutVersion
            .split('/', ':', '.')
            .mapNotNull { segment -> segment.takeIf { it.isNotBlank() }?.let(::sanitizeIdentifier) }
        val worldSegment = sanitizeIdentifier(metadata.runtimeWorld.name).ifEmpty { "World" }
        return buildString {
            append("__wit")
            if (segments.isNotEmpty()) {
                append('/')
                append(segments.joinToString("/"))
            }
            append('/')
            append(worldSegment)
            append(".kt")
        }
    }

    fun bindingHandlerType(): ConeKotlinType = resolveBindingHandlerType()

    private fun resolveBindingHandlerType(): ConeKotlinType =
        session.symbolProvider.getClassLikeSymbolByClassId(BINDING_HANDLER_CLASS_ID)?.let { symbol ->
            when (symbol) {
                is FirTypeAliasSymbol -> {
                    @OptIn(SymbolInternals::class)
                    symbol.fir.expandedTypeRef.coneType
                }
                is FirClassSymbol<*> -> symbol.defaultType()
                else -> error("Unexpected symbol for BindingHandler: ${symbol::class}")
            }
        } ?: error("Unable to resolve BindingHandler type alias for WIT driver generation")

    private class SyntheticLightNode(
        private val elementType: IElementType,
        private val start: Int,
        private val end: Int,
        val debugText: String,
    ) : LighterASTNode {
        override fun getTokenType(): IElementType = elementType
        override fun getStartOffset(): Int = start
        override fun getEndOffset(): Int = end
    }

    private class SingleNodeTreeStructure(
        private val node: SyntheticLightNode,
        private val filePath: String,
    ) : FlyweightCapableTreeStructure<LighterASTNode> {
        override fun getRoot(): LighterASTNode = node

        override fun getParent(node: LighterASTNode): LighterASTNode? = null

        override fun getChildren(parent: LighterASTNode, into: Ref<Array<LighterASTNode>>): Int {
            into.set(emptyArray())
            return 0
        }

        override fun disposeChildren(nodes: Array<LighterASTNode>, count: Int) {}

        override fun toString(node: LighterASTNode): CharSequence {
            val description = (node as? SyntheticLightNode)?.debugText.orEmpty()
            return buildString {
                append(filePath)
                if (description.isNotEmpty()) {
                    append(" :: ")
                    append(description)
                }
            }
        }

        override fun getStartOffset(node: LighterASTNode): Int = node.startOffset

        override fun getEndOffset(node: LighterASTNode): Int = node.endOffset
    }
}
