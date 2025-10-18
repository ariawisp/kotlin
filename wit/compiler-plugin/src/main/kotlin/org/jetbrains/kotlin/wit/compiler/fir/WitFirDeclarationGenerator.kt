package org.jetbrains.kotlin.wit.compiler.fir

import org.jetbrains.kotlin.com.intellij.lang.LighterASTNode
import org.jetbrains.kotlin.com.intellij.openapi.util.Ref
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.com.intellij.util.diff.FlyweightCapableTreeStructure
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtLightSourceElement
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fakeElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataKey
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataRegistry
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.builder.buildValueParameter
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.DeclarationGenerationContext
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.plugin.DeclarationBuildingContext
import org.jetbrains.kotlin.fir.plugin.createCompanionObject
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createMemberProperty
import org.jetbrains.kotlin.fir.plugin.createTopLevelClass
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.deserialization.toQualifiedPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildArgumentList
import org.jetbrains.kotlin.fir.expressions.builder.buildCollectionLiteral
import org.jetbrains.kotlin.fir.expressions.builder.buildEnumEntryDeserializedAccessExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildLiteralExpression
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeAliasSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.types.withNullability
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.wit.compiler.WitPluginOptions
import org.jetbrains.kotlin.wit.compiler.WIT_DRIVER_BIND_FUNCTION_NAME
import org.jetbrains.kotlin.wit.compiler.WIT_DRIVER_OBJECT_SIMPLE_NAME
import org.jetbrains.kotlin.wit.compiler.schema.BindingKind
import org.jetbrains.kotlin.wit.compiler.schema.WitInterfaceMetadata
import org.jetbrains.kotlin.wit.compiler.schema.FunctionKind
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeBinding
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeConstructor
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeInterface
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeResource
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeWorld
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeType
import org.jetbrains.kotlin.wit.compiler.schema.WitSchemaIndex
import org.jetbrains.kotlin.wit.compiler.schema.WitWorldMetadata
import org.jetbrains.kotlin.types.ConstantValueKind

@OptIn(ExperimentalTopLevelDeclarationsGenerationApi::class)
internal class WitFirDeclarationGenerator(
    session: FirSession,
    private val schemaIndex: WitSchemaIndex,
    private val options: WitPluginOptions,
) : FirDeclarationGenerationExtension(session) {

    private val worldDeclarations: Map<ClassId, WorldMetadata>
    private val packages: Set<FqName>
    private val classSourceCache = mutableMapOf<ClassId, KtSourceElement>()
    private val topLevelClassIds: Set<ClassId>
    private val ownHandleResourceType: ConeKotlinType by lazy { resolveOwnHandleResourceType() }

    init {
        val declarations = mutableMapOf<ClassId, WorldMetadata>()
        val packagesSet = mutableSetOf<FqName>()
        val packageMetadataByName = schemaIndex.packageMetadata.associateBy { it.name }

        schemaIndex.runtimeSchema.packages.forEach { runtimePackage ->
            val packageFqName = packageToFqName(runtimePackage.id)
            packagesSet += packageFqName

            val schemaPackage = runtimePackage.metadata ?: packageMetadataByName[runtimePackage.id]
            val runtimeInterfacesByName = runtimePackage.interfaces.associateBy { it.name }
            val schemaInterfacesByName = schemaPackage?.interfaces?.associateBy { it.name }.orEmpty()

            val interfaceBindings = runtimeInterfacesByName.map { (name, runtimeInterface) ->
                interfacePropertyName(name) to InterfaceBindingMetadata(
                    runtime = runtimeInterface,
                    schema = schemaInterfacesByName[name],
                )
            }.toMap()

            val functionInterfaceIndex = buildMap {
                runtimeInterfacesByName.values.forEach { runtimeInterface ->
                    runtimeInterface.functions.forEach { function ->
                        put(function.name, runtimeInterface.name)
                    }
                }
            }

            runtimePackage.worlds.forEach { runtimeWorld ->
                val worldSchema = schemaPackage?.worlds?.firstOrNull { it.name == runtimeWorld.name }
                val classId = ClassId(packageFqName, Name.identifier(sanitizeIdentifier(runtimeWorld.name)))

                val importHostNames = mutableSetOf<String>()
                val importBindings = runtimeWorld.imports.associate { binding ->
                    val propertyName = bindingPropertyName("Import", binding.name)
                    propertyName to createBindingMetadata(
                        binding = binding,
                        direction = BindingDirection.IMPORT,
                        functionInterfaceIndex = functionInterfaceIndex,
                        hostNames = importHostNames,
                    )
                }

                val exportHostNames = mutableSetOf<String>()
                val exportBindings = runtimeWorld.exports.associate { binding ->
                    val propertyName = bindingPropertyName("Export", binding.name)
                    propertyName to createBindingMetadata(
                        binding = binding,
                        direction = BindingDirection.EXPORT,
                        functionInterfaceIndex = functionInterfaceIndex,
                        hostNames = exportHostNames,
                    )
                }

                val resourceHostNames = mutableSetOf<String>()
                val resourceBindings = runtimeInterfacesByName.values.flatMap { runtimeInterface ->
                    runtimeInterface.resources.map { resource ->
                        val hostBase = listOf("resource", runtimeInterface.name, resource.name)
                            .joinToString(separator = "_") { it.trim().ifEmpty { "resource" } }
                        val hostFunction = Name.identifier(allocateHostFunctionName(hostBase, resourceHostNames))
                        resourcePropertyName(runtimeInterface.name, resource.name) to ResourceBindingMetadata(
                            runtimeInterface = runtimeInterface,
                            resource = resource,
                            hostFunctionName = hostFunction,
                        )
                    }
                }.toMap()

                val companionClassId = classId.createNestedClassId(SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT)
                val driverClassId = companionClassId.createNestedClassId(Name.identifier(WIT_DRIVER_OBJECT_SIMPLE_NAME))
                val companionRelativeClassName =
                    classId.relativeClassName.child(SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT)
                val bindCallableId = CallableId(
                    classId.packageFqName,
                    companionRelativeClassName,
                    BIND_FUNCTION_NAME,
                )
                val hasImportHostFunctions = importBindings.values.any { it.hostFunctionName != null }
                val hasExportHostFunctions = exportBindings.values.any { it.hostFunctionName != null }
                val driverImportsClassId = if (hasImportHostFunctions) {
                    driverClassId.createNestedClassId(IMPORTS_INTERFACE_NAME)
                } else {
                    null
                }
                val driverExportsClassId = if (hasExportHostFunctions) {
                    driverClassId.createNestedClassId(EXPORTS_INTERFACE_NAME)
                } else {
                    null
                }
                val driverResourcesClassId = if (resourceBindings.isNotEmpty()) {
                    driverClassId.createNestedClassId(RESOURCES_INTERFACE_NAME)
                } else {
                    null
                }
                val registerImportsCallableId = if (hasImportHostFunctions) {
                    CallableId(
                        classId.packageFqName,
                        companionRelativeClassName,
                        REGISTER_IMPORTS_FUNCTION_NAME,
                    )
                } else {
                    null
                }
                val registerExportsCallableId = if (hasExportHostFunctions) {
                    CallableId(
                        classId.packageFqName,
                        companionRelativeClassName,
                        REGISTER_EXPORTS_FUNCTION_NAME,
                    )
                } else {
                    null
                }
                val registerResourcesCallableId = if (resourceBindings.isNotEmpty()) {
                    CallableId(
                        classId.packageFqName,
                        companionRelativeClassName,
                        REGISTER_RESOURCES_FUNCTION_NAME,
                    )
                } else {
                    null
                }

                val constructorHelpers = buildConstructorHelpers(
                    runtimeWorld,
                    importBindings,
                    exportBindings,
                )

                val metadataEntry = WorldMetadata(
                    packageId = runtimePackage.id,
                    packageFqName = packageFqName,
                    runtimeWorld = runtimeWorld,
                    worldSchema = worldSchema,
                    interfaceBindings = interfaceBindings,
                    importBindings = importBindings,
                    exportBindings = exportBindings,
                    resourceBindings = resourceBindings,
                    constructorHelpers = constructorHelpers,
                    driverCompanionClassId = companionClassId,
                    driverClassId = driverClassId,
                    bindCallableId = bindCallableId,
                    driverImportsClassId = driverImportsClassId,
                    driverExportsClassId = driverExportsClassId,
                    registerImportsCallableId = registerImportsCallableId,
                    registerExportsCallableId = registerExportsCallableId,
                    driverResourcesClassId = driverResourcesClassId,
                    registerResourcesCallableId = registerResourcesCallableId,
                )
                declarations[classId] = metadataEntry
                declarations[companionClassId] = metadataEntry
                declarations[driverClassId] = metadataEntry
                driverImportsClassId?.let { declarations[it] = metadataEntry }
                driverExportsClassId?.let { declarations[it] = metadataEntry }
                driverResourcesClassId?.let { declarations[it] = metadataEntry }
            }
        }

        worldDeclarations = declarations
        packages = packagesSet
        topLevelClassIds = declarations.keys.filterTo(mutableSetOf()) { it.outerClassId == null }

        if (options.debug) {
            println(renderDebugTrace())
        }
    }

    private fun renderDebugTrace(): String {
        if (worldDeclarations.isEmpty()) return "WIT FIR trace: <empty>"
        val sortedWorlds = worldDeclarations.entries.sortedBy { entry ->
            val metadata = entry.value
            "${metadata.packageId}/${metadata.runtimeWorld.name}"
        }
        return buildString {
            append("WIT FIR trace: ")
            append(sortedWorlds.size)
            append(" world(s)")
            sortedWorlds.forEach { (classId, metadata) ->
                appendLine()
                append("  • ")
                append(metadata.packageId)
                append('/')
                append(metadata.runtimeWorld.name)
                append(" -> ")
                append(classId.asString())
                appendLine()
                append("    interfaces (")
                append(metadata.interfaceBindings.size)
                append("):")
                if (metadata.interfaceBindings.isEmpty()) {
                    append(" <none>")
                } else {
                    metadata.interfaceBindings.entries
                        .sortedBy { it.key.asString() }
                        .forEach { (name, interfaceMetadata) ->
                            appendLine()
                            append("      - ")
                            append(name.asString())
                            interfaceMetadata.runtime?.name?.let { runtimeName ->
                                append(" :: ")
                                append(runtimeName)
                            }
                        }
                }
                appendLine()
                append("    bindings (")
                val totalBindings = metadata.importBindings.size + metadata.exportBindings.size
                append(totalBindings)
                append("):")
                if (totalBindings == 0) {
                    append(" <none>")
                } else {
                    val orderedBindings = buildList {
                        metadata.importBindings.entries.forEach { add(it) }
                        metadata.exportBindings.entries.forEach { add(it) }
                    }.sortedBy { it.key.asString() }
                    orderedBindings.forEach { (name, bindingMetadata) ->
                        val runtimeBinding = bindingMetadata.binding
                        appendLine()
                        append("      - ")
                        append(name.asString())
                        append(" :: ")
                        append(bindingMetadata.direction)
                        append('/')
                        append(runtimeBinding.bindingKind)
                        append(" [")
                        append(runtimeBinding.name)
                        append(']')
                        if (runtimeBinding.target.isNotEmpty()) {
                            append(" target=")
                            append(runtimeBinding.target)
                        }
                        bindingMetadata.functionStubName?.let { stub ->
                            append(" stub=")
                            append(stub.asString())
                        }
                        bindingMetadata.hostFunctionName?.let { host ->
                            append(" host=")
                            append(host.asString())
                        }
                        runtimeBinding.signature?.let { signature ->
                            append(" sig=")
                            append(signature.kind)
                            append(" params=")
                            append(renderParameters(signature.parameters))
                            append(" results=")
                            append(renderResults(signature.results))
                            if (signature.isAsync) append(" async")
                            if (signature.usesStreams) append(" streams")
                        }
                    }
                }
                appendLine()
                append("    resources (")
                append(metadata.resourceBindings.size)
                append("):")
                if (metadata.resourceBindings.isEmpty()) {
                    append(" <none>")
                } else {
                    metadata.resourceBindings.entries
                        .sortedBy { it.key.asString() }
                        .forEach { (name, resourceMetadata) ->
                            val resource = resourceMetadata.resource
                            appendLine()
                            append("      - ")
                            append(name.asString())
                            append(" :: ")
                            append(resourceMetadata.runtimeInterface.name)
                            append('/')
                            append(resource.name)
                            resource.ownHandleType?.takeIf { it.isNotEmpty() }?.let {
                                append(" own=")
                                append(it)
                            }
                            resource.borrowHandleType?.takeIf { it.isNotEmpty() }?.let {
                                append(" borrow=")
                                append(it)
                            }
                        }
                }
                appendLine()
                val constructors = metadata.runtimeWorld.constructors
                append("    constructors (")
                append(constructors.size)
                append("):")
                if (constructors.isEmpty()) {
                    append(" <none>")
                } else {
                    constructors.forEach { constructor ->
                        appendLine()
                        append("      - ")
                        append(constructor.bindingName)
                        metadata.constructorDirection(constructor.bindingName)?.let { direction ->
                            append(" :: ")
                            append(direction)
                        }
                        val signature = constructor.signature
                        append(" sig=")
                        append(signature.kind)
                        append(" params=")
                        append(renderParameters(signature.parameters))
                        append(" results=")
                        append(renderResults(signature.results))
                        if (signature.isAsync) append(" async")
                        if (signature.usesStreams) append(" streams")
                    }
                }
                appendLine()
                append("    driver: companion=")
                append(metadata.driverCompanionClassId.asString())
                append(" class=")
                append(metadata.driverClassId.asString())
                append(" bind=")
                append(metadata.bindCallableId)
            }
        }
    }

    private fun ensureWorldSource(classId: ClassId, metadata: WorldMetadata): KtSourceElement {
        return classSourceCache[classId] ?: createWorldSyntheticSource(metadata).also { synthetic ->
            recordClassSource(classId, synthetic)
        }
    }

    private fun recordClassSource(classId: ClassId, source: KtSourceElement?) {
        if (source != null) {
            classSourceCache[classId] = source
        }
    }

    private fun DeclarationBuildingContext<*>.setMemberSource(ownerSymbol: FirClassSymbol<*>) {
        val ownerSource = classSourceCache[ownerSymbol.classId]
        source = ownerSource?.fakeElement(KtFakeSourceElementKind.PluginGenerated)
    }

    private fun createWorldSyntheticSource(metadata: WorldMetadata): KtLightSourceElement {
        val filePath = buildSyntheticFilePath(metadata)
        val descriptor = "${metadata.packageId}/${metadata.runtimeWorld.name}"
        val debugText = "synthetic WIT declaration: $descriptor"
        val endOffset = debugText.length.coerceAtLeast(1)
        val node = SyntheticLightNode(
            elementType = KtNodeTypes.CLASS,
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

    private fun renderParameters(parameters: List<WitRuntimeType>): String =
        if (parameters.isEmpty()) {
            "()"
        } else {
            parameters.joinToString(prefix = "(", postfix = ")", separator = ", ") { parameter ->
                val label = parameter.label?.takeIf { it.isNotBlank() } ?: "_"
                "$label:${parameter.typeRef}"
            }
        }

    private fun renderResults(results: List<WitRuntimeType>): String =
        if (results.isEmpty()) {
            "()"
        } else {
            results.joinToString(prefix = "(", postfix = ")", separator = ", ") { it.typeRef }
        }

    private fun WorldMetadata.constructorDirection(bindingName: String): BindingDirection? {
        importBindings.values.firstOrNull { it.binding.name == bindingName }?.let { return it.direction }
        exportBindings.values.firstOrNull { it.binding.name == bindingName }?.let { return it.direction }
        return null
    }

    override fun hasPackage(packageFqName: FqName): Boolean = packageFqName in packages

    override fun getTopLevelClassIds(): Set<ClassId> = topLevelClassIds

    override fun generateTopLevelClassLikeDeclaration(classId: ClassId): FirClassLikeSymbol<*>? {
        if (classId.outerClassId != null) return null
        val metadata = worldDeclarations[classId] ?: return null
        val worldSource = ensureWorldSource(classId.outermostClassId, metadata)
        val classSource = if (classId.outerClassId == null) {
            worldSource
        } else {
            worldSource.fakeElement(KtFakeSourceElementKind.PluginGenerated)
        }
        val worldClass = createTopLevelClass(classId, WitWorldDeclarationKey) {
            source = classSource
            modality = Modality.ABSTRACT
        }.apply {
            buildWitWorldAnnotation(metadata)?.let { annotation ->
                replaceAnnotations(annotations + annotation)
            }
        }
        recordClassSource(classId, worldClass.source ?: classSource)
        worldClass.witWorldMetadata = metadata
        return worldClass.symbol
    }

    @OptIn(SymbolInternals::class)
    override fun getNestedClassifiersNames(
        classSymbol: FirClassSymbol<*>,
        context: DeclarationGenerationContext.Nested,
    ): Set<Name> {
        val pluginOrigin = classSymbol.origin as? FirDeclarationOrigin.Plugin ?: return emptySet()
        val metadata = worldDeclarations[classSymbol.classId]
            ?: (classSymbol.fir as? FirRegularClass)?.witWorldMetadata
            ?: return emptySet()
        return when (pluginOrigin.key) {
            WitWorldDeclarationKey -> setOf(SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT)
            WitWorldDriverCompanionKey -> setOf(DRIVER_OBJECT_NAME)
            WitWorldDriverClassKey -> buildSet<Name> {
                metadata.driverImportsClassId?.let { add(IMPORTS_INTERFACE_NAME) }
                metadata.driverExportsClassId?.let { add(EXPORTS_INTERFACE_NAME) }
                metadata.driverResourcesClassId?.let { add(RESOURCES_INTERFACE_NAME) }
            }
            else -> emptySet()
        }
    }

    override fun generateNestedClassLikeDeclaration(
        owner: FirClassSymbol<*>,
        name: Name,
        context: DeclarationGenerationContext.Nested,
    ): FirClassLikeSymbol<*>? {
        val pluginOrigin = owner.origin as? FirDeclarationOrigin.Plugin ?: return null
        val metadata = worldDeclarations[owner.classId] ?: return null
        return when (pluginOrigin.key) {
            WitWorldDeclarationKey -> {
                if (name != SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT) return null
                val companionSource = classSourceCache[owner.classId] ?: owner.source
                val companion = createCompanionObject(owner, WitWorldDriverCompanionKey) {
                    source = companionSource
                }
                recordClassSource(companion.symbol.classId, companion.source ?: companionSource)
                companion.witWorldMetadata = metadata
                companion.symbol
            }
            WitWorldDriverCompanionKey -> {
                if (name != DRIVER_OBJECT_NAME) return null
                val driverSuperType =
                    session.symbolProvider.getClassLikeSymbolByClassId(WORLD_DRIVER_CLASS_ID)?.defaultType()
                        ?: return null
                val driverSource = classSourceCache[owner.classId]?.fakeElement(KtFakeSourceElementKind.PluginGenerated)
                val driverClass = createNestedClass(
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
                driverClass.witWorldMetadata = metadata
                configureDriverObject(driverClass, metadata)
                driverClass.symbol
            }
            WitWorldDriverClassKey -> when (name) {
                IMPORTS_INTERFACE_NAME -> generateDriverContractInterface(owner, metadata, BindingDirection.IMPORT)
                EXPORTS_INTERFACE_NAME -> generateDriverContractInterface(owner, metadata, BindingDirection.EXPORT)
                RESOURCES_INTERFACE_NAME -> generateDriverResourcesInterface(owner, metadata)
                else -> null
            }
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
        val interfaceClass = createNestedClass(
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
        val interfaceClass = createNestedClass(
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

    @OptIn(SymbolInternals::class)
    override fun getCallableNamesForClass(
        classSymbol: FirClassSymbol<*>,
        context: MemberGenerationContext,
    ): Set<Name> {
        val regularClassSymbol = classSymbol as? FirRegularClassSymbol ?: return emptySet()
        val pluginOrigin = regularClassSymbol.origin as? FirDeclarationOrigin.Plugin ?: return emptySet()
        val metadata = when (pluginOrigin.key) {
            WitWorldDeclarationKey -> worldDeclarations[regularClassSymbol.classId]
            WitWorldDriverCompanionKey -> regularClassSymbol.fir.witWorldMetadata
                ?: regularClassSymbol.classId.outerClassId?.let(worldDeclarations::get)
            else -> null
        } ?: return emptySet()
        return when (pluginOrigin.key) {
            WitWorldDeclarationKey -> buildSet {
                add(WORLD_NAME_PROPERTY)
                addAll(metadata.interfaceBindings.keys)
                addAll(metadata.importBindings.keys)
                addAll(metadata.exportBindings.keys)
                addAll(metadata.resourceBindings.keys)
                metadata.importBindings.values.mapNotNullTo(this) { it.functionStubName }
                metadata.exportBindings.values.mapNotNullTo(this) { it.functionStubName }
            }
            WitWorldDriverCompanionKey -> buildSet {
                add(RUNTIME_SLOT_PROPERTY)
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

    @OptIn(SymbolInternals::class)
    override fun generateProperties(
        callableId: CallableId,
        context: MemberGenerationContext?,
    ): List<FirPropertySymbol> {
        val ownerSymbol = context?.owner as? FirRegularClassSymbol ?: return emptyList()
        val pluginOrigin = ownerSymbol.origin as? FirDeclarationOrigin.Plugin ?: return emptyList()
        val metadata = when (pluginOrigin.key) {
            WitWorldDeclarationKey -> ownerSymbol.fir.witWorldMetadata ?: worldDeclarations[ownerSymbol.classId]
            WitWorldDriverCompanionKey -> ownerSymbol.fir.witWorldMetadata
                ?: ownerSymbol.classId.outerClassId?.let(worldDeclarations::get)
            else -> null
        } ?: return emptyList()

        val callableName = callableId.callableName
        return when (pluginOrigin.key) {
            WitWorldDeclarationKey -> generateWorldProperties(ownerSymbol, metadata, callableName)
            WitWorldDriverCompanionKey -> generateCompanionProperties(ownerSymbol, metadata, callableName)
            else -> emptyList()
        }
    }

    private fun generateWorldProperties(
        owner: FirRegularClassSymbol,
        metadata: WorldMetadata,
        callableName: Name,
    ): List<FirPropertySymbol> {
        if (callableName == WORLD_NAME_PROPERTY) {
            val property = createMemberProperty(
                owner,
                WitWorldDeclarationKey,
                WORLD_NAME_PROPERTY,
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
            val property = createMemberProperty(
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
            val property = createMemberProperty(
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
            buildWitBindingAnnotation(bindingMetadata)?.let { annotation ->
                property.replaceAnnotations(property.annotations + annotation)
            }
            return listOf(property.symbol)
        }

        metadata.resourceBindings[callableName]?.let { resourceMetadata ->
            val property = createMemberProperty(
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
            buildWitResourceAnnotation(resourceMetadata)?.let { annotation ->
                property.replaceAnnotations(property.annotations + annotation)
            }
            return listOf(property.symbol)
        }

        return emptyList()
    }

    private fun generateCompanionProperties(
        owner: FirRegularClassSymbol,
        metadata: WorldMetadata,
        callableName: Name,
    ): List<FirPropertySymbol> {
        if (callableName != RUNTIME_SLOT_PROPERTY) return emptyList()
        val componentRuntimeSymbol =
            session.symbolProvider.getClassLikeSymbolByClassId(COMPONENT_RUNTIME_CLASS_ID) as? FirClassSymbol<*>
        val propertyType = componentRuntimeSymbol?.defaultType()?.withNullability(nullable = true, session.typeContext)
            ?: session.builtinTypes.anyType.coneType.withNullability(nullable = true, session.typeContext)
        val property = createMemberProperty(
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

    @OptIn(SymbolInternals::class)
    override fun generateFunctions(
        callableId: CallableId,
        context: MemberGenerationContext?,
    ): List<FirNamedFunctionSymbol> {
        val ownerSymbol = context?.owner as? FirRegularClassSymbol ?: return emptyList()
        val pluginOrigin = ownerSymbol.origin as? FirDeclarationOrigin.Plugin ?: return emptyList()
        val metadata = when (pluginOrigin.key) {
            WitWorldDeclarationKey -> worldDeclarations[ownerSymbol.classId]
            WitWorldDriverCompanionKey -> ownerSymbol.fir.witWorldMetadata
                ?: ownerSymbol.classId.outerClassId?.let(worldDeclarations::get)
            WitWorldDriverImportsKey,
            WitWorldDriverExportsKey,
            WitWorldDriverResourcesKey -> ownerSymbol.fir.witWorldMetadata
                ?: ownerSymbol.classId.outerClassId?.let(worldDeclarations::get)
            else -> null
        } ?: return emptyList()

        return when (pluginOrigin.key) {
            WitWorldDriverCompanionKey -> {
                metadata.findConstructorHelper(callableId.callableName)?.let { helper ->
                    generateConstructorHelperFunction(ownerSymbol, metadata, helper)?.let(::listOf) ?: emptyList()
                } ?: generateDriverCompanionFunctions(callableId, ownerSymbol, metadata)
            }
            WitWorldDriverImportsKey -> generateDriverContractFunctions(
                callableId,
                ownerSymbol,
                metadata,
                BindingDirection.IMPORT,
            )
            WitWorldDriverExportsKey -> generateDriverContractFunctions(
                callableId,
                ownerSymbol,
                metadata,
                BindingDirection.EXPORT,
            )
            WitWorldDriverResourcesKey -> generateDriverResourceFunctions(callableId, ownerSymbol, metadata)
            WitWorldDeclarationKey -> generateWorldFunctions(callableId, ownerSymbol, metadata)
            else -> emptyList()
        }
    }

    private fun generateDriverContractFunctions(
        callableId: CallableId,
        ownerSymbol: FirRegularClassSymbol,
        metadata: WorldMetadata,
        direction: BindingDirection,
    ): List<FirNamedFunctionSymbol> {
        val bindingMetadata = metadata.findBindingByFunctionName(callableId.callableName) ?: return emptyList()
        if (bindingMetadata.direction != direction) return emptyList()
        if (bindingMetadata.hostFunctionName != callableId.callableName) return emptyList()
        val signature = bindingMetadata.binding.signature ?: return emptyList()

        val returnType = if (signature.results.isEmpty()) {
            session.builtinTypes.unitType.coneType
        } else {
            session.builtinTypes.anyType.coneType
        }

        val key = when (direction) {
            BindingDirection.IMPORT -> WitWorldDriverImportsKey
            BindingDirection.EXPORT -> WitWorldDriverExportsKey
        }

        val function = createMemberFunction(ownerSymbol, key, callableId.callableName, returnType) {
            setMemberSource(ownerSymbol)
            modality = Modality.ABSTRACT
            visibility = Visibilities.Public
            signature.parameters.forEachIndexed { index, parameter ->
                val parameterName = Name.identifier(sanitizeParameterName(parameter.label, index))
                valueParameter(parameterName, session.builtinTypes.anyType.coneType)
            }
        }

        buildWitBindingAnnotation(bindingMetadata)?.let { annotation ->
            function.replaceAnnotations(function.annotations + annotation)
        }
        function.witWorldFunctionMetadata = metadata

        return listOf(function.symbol)
    }

    private fun generateDriverResourceFunctions(
        callableId: CallableId,
        ownerSymbol: FirRegularClassSymbol,
        metadata: WorldMetadata,
    ): List<FirNamedFunctionSymbol> {
        val resourceMetadata = metadata.findResourceByFunctionName(callableId.callableName) ?: return emptyList()
        val resourceFactoryType = resolveResourceFactoryType()
        val function = createMemberFunction(ownerSymbol, WitWorldDriverResourcesKey, callableId.callableName, resourceFactoryType) {
            setMemberSource(ownerSymbol)
            modality = Modality.ABSTRACT
            visibility = Visibilities.Public
        }

        buildWitResourceAnnotation(resourceMetadata)?.let { annotation ->
            function.replaceAnnotations(function.annotations + annotation)
        }
        function.witWorldFunctionMetadata = metadata

        return listOf(function.symbol)
    }

    private fun buildConstructorHelpers(
        runtimeWorld: WitRuntimeWorld,
        importBindings: Map<Name, BindingMetadata>,
        exportBindings: Map<Name, BindingMetadata>,
    ): Map<Name, ConstructorHelperMetadata> {
        if (runtimeWorld.constructors.isEmpty()) return emptyMap()
        val helpers = linkedMapOf<Name, ConstructorHelperMetadata>()
        val usedNames = mutableSetOf<String>()
        runtimeWorld.constructors.forEach { constructor ->
            val importBinding = importBindings.values.firstOrNull { it.binding.name == constructor.bindingName }
                ?.let { it to BindingDirection.IMPORT }
            val exportBinding = exportBindings.values.firstOrNull { it.binding.name == constructor.bindingName }
                ?.let { it to BindingDirection.EXPORT }
            val (bindingMetadata, direction) = importBinding ?: exportBinding ?: return@forEach
            if (direction != BindingDirection.EXPORT) return@forEach
            val helperName = allocateConstructorHelperName(bindingMetadata.resourceName ?: constructor.bindingName, usedNames)
            helpers[helperName] = ConstructorHelperMetadata(
                functionName = helperName,
                constructor = constructor,
                binding = bindingMetadata,
                direction = direction,
            )
        }
        return helpers
    }

    private fun generateConstructorHelperFunction(
        ownerSymbol: FirRegularClassSymbol,
        metadata: WorldMetadata,
        helper: ConstructorHelperMetadata,
    ): FirNamedFunctionSymbol? {
        val componentRuntimeSymbol =
            session.symbolProvider.getClassLikeSymbolByClassId(COMPONENT_RUNTIME_CLASS_ID) as? FirClassSymbol<*>
                ?: return null
        val resourceFactoryType = resolveResourceFactoryType()

        val function = createMemberFunction(
            ownerSymbol,
            WitWorldDriverCompanionKey,
            helper.functionName,
            ownHandleResourceType,
        ) {
            setMemberSource(ownerSymbol)
            modality = Modality.FINAL
            visibility = Visibilities.Public
            status { isOverride = false }
            valueParameter(RUNTIME_PARAMETER_NAME, componentRuntimeSymbol.defaultType())
            valueParameter(Name.identifier("factory"), resourceFactoryType)
            helper.constructor.signature.parameters.forEachIndexed { index, parameter ->
                val parameterName = Name.identifier(sanitizeParameterName(parameter.label, index))
                valueParameter(parameterName, session.builtinTypes.anyType.coneType)
            }
        }
        function.witWorldFunctionMetadata = metadata
        buildWitConstructorAnnotation(metadata, helper.constructor)?.let { annotation ->
            function.replaceAnnotations(function.annotations + annotation)
        }
        return function.symbol
    }

    private fun generateDriverCompanionFunctions(
        callableId: CallableId,
        ownerSymbol: FirRegularClassSymbol,
        metadata: WorldMetadata,
    ): List<FirNamedFunctionSymbol> {
        val componentRuntimeSymbol =
            session.symbolProvider.getClassLikeSymbolByClassId(COMPONENT_RUNTIME_CLASS_ID) as? FirClassSymbol<*>
                ?: return emptyList()

        return when (callableId.callableName) {
            BIND_FUNCTION_NAME -> {
                val function = createMemberFunction(
                    ownerSymbol,
                    WitWorldDriverCompanionKey,
                    BIND_FUNCTION_NAME,
                    session.builtinTypes.unitType.coneType,
                ) {
                    setMemberSource(ownerSymbol)
                    modality = Modality.FINAL
                    visibility = Visibilities.Public
                    status { isOverride = false }
                    valueParameter(RUNTIME_PARAMETER_NAME, componentRuntimeSymbol.defaultType())
                }
                function.witWorldFunctionMetadata = metadata
                listOf(function.symbol)
            }
            REGISTER_IMPORTS_FUNCTION_NAME -> {
                generateDriverRegisterFunction(
                    ownerSymbol,
                    metadata,
                    componentRuntimeSymbol,
                    direction = BindingDirection.IMPORT,
                )?.let(::listOf) ?: emptyList()
            }
            REGISTER_EXPORTS_FUNCTION_NAME -> {
                generateDriverRegisterFunction(
                    ownerSymbol,
                    metadata,
                    componentRuntimeSymbol,
                    direction = BindingDirection.EXPORT,
                )?.let(::listOf) ?: emptyList()
            }
            REGISTER_RESOURCES_FUNCTION_NAME -> {
                generateDriverRegisterResourcesFunction(
                    ownerSymbol,
                    metadata,
                    componentRuntimeSymbol,
                )?.let(::listOf) ?: emptyList()
            }
            else -> emptyList()
        }
    }

    private fun generateDriverRegisterFunction(
        ownerSymbol: FirRegularClassSymbol,
        metadata: WorldMetadata,
        componentRuntimeSymbol: FirClassSymbol<*>,
        direction: BindingDirection,
    ): FirNamedFunctionSymbol? {
        val contractClassId = when (direction) {
            BindingDirection.IMPORT -> metadata.driverImportsClassId
            BindingDirection.EXPORT -> metadata.driverExportsClassId
        } ?: return null
        val contractSymbol =
            session.symbolProvider.getClassLikeSymbolByClassId(contractClassId) as? FirClassSymbol<*>
                ?: return null
        val callableName = when (direction) {
            BindingDirection.IMPORT -> REGISTER_IMPORTS_FUNCTION_NAME
            BindingDirection.EXPORT -> REGISTER_EXPORTS_FUNCTION_NAME
        }

        val function = createMemberFunction(
            ownerSymbol,
            WitWorldDriverCompanionKey,
            callableName,
            session.builtinTypes.unitType.coneType,
        ) {
            setMemberSource(ownerSymbol)
            modality = Modality.FINAL
            visibility = Visibilities.Public
            status { isOverride = false }
            valueParameter(RUNTIME_PARAMETER_NAME, componentRuntimeSymbol.defaultType())
            valueParameter(IMPLEMENTATION_PARAMETER_NAME, contractSymbol.defaultType())
        }
        function.witWorldFunctionMetadata = metadata
        return function.symbol
    }

    private fun generateDriverRegisterResourcesFunction(
        ownerSymbol: FirRegularClassSymbol,
        metadata: WorldMetadata,
        componentRuntimeSymbol: FirClassSymbol<*>,
    ): FirNamedFunctionSymbol? {
        val contractClassId = metadata.driverResourcesClassId ?: return null
        val contractSymbol =
            session.symbolProvider.getClassLikeSymbolByClassId(contractClassId) as? FirClassSymbol<*>
                ?: return null

        val function = createMemberFunction(
            ownerSymbol,
            WitWorldDriverCompanionKey,
            REGISTER_RESOURCES_FUNCTION_NAME,
            session.builtinTypes.unitType.coneType,
        ) {
            setMemberSource(ownerSymbol)
            modality = Modality.FINAL
            visibility = Visibilities.Public
            status { isOverride = false }
            valueParameter(RUNTIME_PARAMETER_NAME, componentRuntimeSymbol.defaultType())
            valueParameter(IMPLEMENTATION_PARAMETER_NAME, contractSymbol.defaultType())
        }
        function.witWorldFunctionMetadata = metadata
        return function.symbol
    }

    private fun generateWorldFunctions(
        callableId: CallableId,
        ownerSymbol: FirRegularClassSymbol,
        metadata: WorldMetadata,
    ): List<FirNamedFunctionSymbol> {
        val bindingMetadata = metadata.findBindingByFunctionName(callableId.callableName) ?: return emptyList()
        val signature = bindingMetadata.binding.signature ?: return emptyList()

        val returnType = if (signature.results.isEmpty()) {
            session.builtinTypes.unitType.coneType
        } else {
            session.builtinTypes.anyType.coneType
        }

        val function = createMemberFunction(ownerSymbol, WitWorldDeclarationKey, callableId.callableName, returnType) {
            setMemberSource(ownerSymbol)
            modality = Modality.ABSTRACT
            visibility = Visibilities.Public
            signature.parameters.forEachIndexed { index, parameter ->
                val parameterName = Name.identifier(sanitizeParameterName(parameter.label, index))
                valueParameter(parameterName, session.builtinTypes.anyType.coneType)
            }
        }

        buildWitBindingAnnotation(bindingMetadata)?.let { annotation ->
            function.replaceAnnotations(function.annotations + annotation)
        }
        function.witWorldFunctionMetadata = metadata

        return listOf(function.symbol)
    }

    private fun createBindingMetadata(
        binding: WitRuntimeBinding,
        direction: BindingDirection,
        functionInterfaceIndex: Map<String, String>,
        hostNames: MutableSet<String>,
    ): BindingMetadata {
        val stubName = functionStubName(binding, direction)
        val owner = resolveBindingOwner(binding, functionInterfaceIndex)
        val signature = binding.signature
        val hostFunctionName = if (stubName != null && signature != null) {
            val baseName = computeHostFunctionBaseName(binding, owner.interfaceName, owner.resourceName)
            val allocated = allocateHostFunctionName(baseName, hostNames)
            Name.identifier(allocated)
        } else {
            null
        }
        return BindingMetadata(
            binding = binding,
            direction = direction,
            functionStubName = stubName,
            hostFunctionName = hostFunctionName,
            interfaceName = owner.interfaceName,
            resourceName = owner.resourceName,
        )
    }

    private fun computeHostFunctionBaseName(
        binding: WitRuntimeBinding,
        interfaceName: String?,
        resourceName: String?,
    ): String {
        val signature = binding.signature ?: return binding.name
        val parts = parseWitFunctionName(signature.name)
        val cleanedInterface = interfaceName?.takeIf { it.isNotBlank() }
        val cleanedResource = resourceName?.takeIf { it.isNotBlank() }
        return when (signature.kind) {
            FunctionKind.METHOD -> {
                val segments = mutableListOf<String>()
                (cleanedResource ?: parts.scope?.takeIf { it.isNotBlank() })?.let { segments += it }
                parts.member.takeIf { it.isNotBlank() && it != segments.lastOrNull() }?.let { segments += it }
                segments.takeIf { it.isNotEmpty() }?.joinToString("_") ?: binding.name
            }
            FunctionKind.CONSTRUCTOR -> {
                (cleanedResource ?: parts.scope ?: binding.name).ifEmpty { binding.name }
            }
            FunctionKind.STATIC -> {
                val segments = mutableListOf<String>()
                cleanedInterface?.let { segments += it }
                parts.member.takeIf { it.isNotBlank() }?.let { segments += it }
                segments.takeIf { it.isNotEmpty() }?.joinToString("_") ?: binding.name
            }
            else -> binding.name
        }
    }

    private data class BindingOwner(
        val interfaceName: String?,
        val resourceName: String?,
    )

    private data class WitFunctionNameParts(
        val prefix: String?,
        val scope: String?,
        val member: String,
    )

    private fun parseWitFunctionName(rawName: String): WitFunctionNameParts {
        if (!rawName.startsWith("[")) {
            return WitFunctionNameParts(prefix = null, scope = null, member = rawName)
        }
        val closingIndex = rawName.indexOf(']')
        if (closingIndex <= 0) {
            return WitFunctionNameParts(prefix = null, scope = null, member = rawName)
        }
        val prefix = rawName.substring(1, closingIndex)
        val remainder = rawName.substring(closingIndex + 1)
        val scope = when {
            prefix == "constructor" -> remainder.takeIf { it.isNotBlank() }
            remainder.contains('.') -> remainder.substringBefore('.').takeIf { it.isNotBlank() }
            else -> null
        }
        val member = when {
            remainder.contains('.') -> remainder.substringAfter('.')
            else -> remainder
        }.ifBlank { remainder }
        return WitFunctionNameParts(
            prefix = prefix,
            scope = scope,
            member = member,
        )
    }

    private fun resolveBindingOwner(
        binding: WitRuntimeBinding,
        functionInterfaceIndex: Map<String, String>,
    ): BindingOwner {
        return when (binding.bindingKind) {
            BindingKind.INTERFACE -> BindingOwner(
                interfaceName = binding.target.takeIf { it.isNotBlank() },
                resourceName = null,
            )
            BindingKind.RESOURCE -> BindingOwner(
                interfaceName = null,
                resourceName = binding.target.takeIf { it.isNotBlank() },
            )
            BindingKind.FUNCTION -> {
                val signature = binding.signature
                val interfaceName = signature?.name?.let { functionInterfaceIndex[it] }
                val resourceName = signature?.let { parseWitFunctionName(it.name).scope }
                BindingOwner(
                    interfaceName = interfaceName,
                    resourceName = resourceName,
                )
            }
        }
    }

    private fun allocateHostFunctionName(
        rawBaseName: String,
        usedNames: MutableSet<String>,
    ): String {
        val sanitizedBase = sanitizeIdentifier(rawBaseName).ifEmpty { "binding" }
        var candidate = sanitizedBase
        var suffix = 1
        while (!usedNames.add(candidate)) {
            candidate = "${sanitizedBase}_${suffix++}"
        }
        return candidate
    }

    private fun configureDriverObject(
        driverClass: FirRegularClass,
        metadata: WorldMetadata,
    ) {
        driverClass.witWorldMetadata = metadata
        val driverClassSymbol = driverClass.symbol
        val componentRuntimeSymbol =
            session.symbolProvider.getClassLikeSymbolByClassId(COMPONENT_RUNTIME_CLASS_ID) as? FirClassSymbol<*>
                ?: return
        val bindingHandlerType = resolveBindingHandlerType()

        val packageProperty = createMemberProperty(
            driverClassSymbol,
            WitWorldDriverClassKey,
            DRIVER_PACKAGE_ID_PROPERTY,
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

        val worldNameProperty = createMemberProperty(
            driverClassSymbol,
            WitWorldDriverClassKey,
            DRIVER_WORLD_NAME_PROPERTY,
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

        val driverBindFunction = createMemberFunction(
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

        val registerImportFunction = createMemberFunction(
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

        val registerExportFunction = createMemberFunction(
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

    override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
        val owner = context.owner as? FirRegularClassSymbol ?: return emptyList()
        val metadata = worldDeclarations[owner.classId] ?: return emptyList()
        val constructors = metadata.runtimeWorld.constructors
        if (constructors.isEmpty()) return emptyList()

        return constructors.mapIndexed { index, runtimeConstructor ->
            val constructor = createConstructor(
                owner,
                WitWorldDeclarationKey,
                isPrimary = index == 0,
                generateDelegatedNoArgConstructorCall = false,
            ) {
                setMemberSource(owner)
                visibility = Visibilities.Public
            }

            val valueParameters = runtimeConstructor.signature.parameters.mapIndexed { parameterIndex, parameter ->
                buildValueParameter {
                    resolvePhase = FirResolvePhase.BODY_RESOLVE
                    moduleData = session.moduleData
                    origin = WitWorldDeclarationKey.origin
                    name = Name.identifier(sanitizeParameterName(parameter.label, parameterIndex))
                    val parameterType = session.builtinTypes.anyType.coneType
                    returnTypeRef = parameterType.toFirResolvedTypeRef()
                    symbol = FirValueParameterSymbol()
                    containingDeclarationSymbol = constructor.symbol
                }
            }
            constructor.replaceValueParameters(valueParameters)
            constructor.witConstructorMetadata = runtimeConstructor
            buildWitConstructorAnnotation(metadata, runtimeConstructor)?.let { annotation ->
                constructor.replaceAnnotations(constructor.annotations + annotation)
            }
            constructor.symbol
        }
    }

    private fun buildWitWorldAnnotation(metadata: WorldMetadata): FirAnnotation? {
        val annotationClass =
            session.symbolProvider.getClassLikeSymbolByClassId(WIT_WORLD_CLASS_ID) as? FirClassSymbol<*>
                ?: return null
        return buildAnnotation {
            annotationTypeRef = annotationClass.defaultType().toFirResolvedTypeRef()
            argumentMapping = buildAnnotationArgumentMapping {
                mapping[Name.identifier("packageId")] = buildStringLiteral(metadata.packageId)
                mapping[Name.identifier("worldName")] = buildStringLiteral(metadata.runtimeWorld.name)
            }
        }
    }

    private fun buildWitBindingAnnotation(bindingMetadata: BindingMetadata): FirAnnotation? {
        val annotationClass =
            session.symbolProvider.getClassLikeSymbolByClassId(WIT_BINDING_CLASS_ID) as? FirClassSymbol<*>
                ?: return null
        val directionExpression =
            buildEnumEntryExpression(WIT_BINDING_DIRECTION_CLASS_ID, bindingMetadata.direction.name)
                ?: return null
        val kindExpression =
            buildEnumEntryExpression(
                WIT_BINDING_KIND_CLASS_ID,
                bindingMetadata.binding.bindingKind.name,
            ) ?: return null
        val binding = bindingMetadata.binding
        return buildAnnotation {
            annotationTypeRef = annotationClass.defaultType().toFirResolvedTypeRef()
            argumentMapping = buildAnnotationArgumentMapping {
                mapping[Name.identifier("direction")] = directionExpression
                mapping[Name.identifier("kind")] = kindExpression
                mapping[Name.identifier("bindingName")] = buildStringLiteral(binding.name)
                val runtimeTarget = binding.target
                if (runtimeTarget.isNotEmpty()) {
                    mapping[Name.identifier("runtimeTarget")] = buildStringLiteral(runtimeTarget)
                }
                bindingMetadata.interfaceName?.takeIf { it.isNotEmpty() }?.let { interfaceName ->
                    mapping[Name.identifier("interfaceName")] = buildStringLiteral(interfaceName)
                }
                bindingMetadata.resourceName?.takeIf { it.isNotEmpty() }?.let { resourceName ->
                    mapping[Name.identifier("resourceName")] = buildStringLiteral(resourceName)
                }
                if (binding.signature?.isAsync == true) {
                    mapping[Name.identifier("isAsync")] = buildBooleanLiteral(true)
                }
                if (binding.signature?.usesStreams == true) {
                    mapping[Name.identifier("usesStreams")] = buildBooleanLiteral(true)
                }
                val parameters = binding.signature?.parameters.orEmpty()
                mapping[Name.identifier("parameterTypeRefs")] =
                    buildStringArrayLiteral(parameters.map { it.typeRef })
                mapping[Name.identifier("parameterLabels")] =
                    buildStringArrayLiteral(parameters.map { it.label.orEmpty() })
                val results = binding.signature?.results.orEmpty()
                mapping[Name.identifier("resultTypeRefs")] =
                    buildStringArrayLiteral(results.map { it.typeRef })
                mapping[Name.identifier("resultLabels")] =
                    buildStringArrayLiteral(results.map { it.label.orEmpty() })
            }
        }
    }

    private fun buildWitResourceAnnotation(resourceMetadata: ResourceBindingMetadata): FirAnnotation? {
        val annotationClass =
            session.symbolProvider.getClassLikeSymbolByClassId(WIT_RESOURCE_CLASS_ID) as? FirClassSymbol<*>
                ?: return null
        val resource = resourceMetadata.resource
        return buildAnnotation {
            annotationTypeRef = annotationClass.defaultType().toFirResolvedTypeRef()
            argumentMapping = buildAnnotationArgumentMapping {
                mapping[Name.identifier("interfaceName")] =
                    buildStringLiteral(resourceMetadata.runtimeInterface.name)
                mapping[Name.identifier("resourceName")] = buildStringLiteral(resource.name)
                resource.ownHandleType?.takeIf { it.isNotEmpty() }?.let { own ->
                    mapping[Name.identifier("ownHandleType")] = buildStringLiteral(own)
                }
                resource.borrowHandleType?.takeIf { it.isNotEmpty() }?.let { borrow ->
                    mapping[Name.identifier("borrowHandleType")] = buildStringLiteral(borrow)
                }
            }
        }
    }

    private fun buildWitConstructorAnnotation(
        metadata: WorldMetadata,
        runtimeConstructor: WitRuntimeConstructor,
    ): FirAnnotation? {
        val annotationClass =
            session.symbolProvider.getClassLikeSymbolByClassId(WIT_CONSTRUCTOR_CLASS_ID) as? FirClassSymbol<*>
                ?: return null
        val bindingDirection =
            metadata.importBindings.values.firstOrNull { it.binding.name == runtimeConstructor.bindingName }?.direction
                ?: metadata.exportBindings.values.firstOrNull { it.binding.name == runtimeConstructor.bindingName }?.direction
                ?: return null
        val directionExpression =
            buildEnumEntryExpression(WIT_BINDING_DIRECTION_CLASS_ID, bindingDirection.name)
                ?: return null
        return buildAnnotation {
            annotationTypeRef = annotationClass.defaultType().toFirResolvedTypeRef()
            argumentMapping = buildAnnotationArgumentMapping {
                mapping[Name.identifier("bindingName")] =
                    buildStringLiteral(runtimeConstructor.bindingName)
                mapping[Name.identifier("direction")] = directionExpression
            }
        }
    }

    private fun buildEnumEntryExpression(classId: ClassId, entryName: String): FirExpression? {
        if (session.symbolProvider.getClassLikeSymbolByClassId(classId) == null) return null
        return buildEnumEntryDeserializedAccessExpression {
            enumClassId = classId
            enumEntryName = Name.identifier(entryName)
        }.toQualifiedPropertyAccessExpression(session)
    }

    private fun buildStringLiteral(value: String): FirExpression =
        buildLiteralExpression(
            source = null,
            kind = ConstantValueKind.String,
            value = value,
            setType = true,
        )

    private fun buildStringArrayLiteral(values: List<String>): FirExpression =
        buildCollectionLiteral {
            argumentList = buildArgumentList {
                values.forEach { value ->
                    arguments += buildStringLiteral(value)
                }
            }
        }

    private fun buildBooleanLiteral(value: Boolean): FirExpression =
        buildLiteralExpression(
            source = null,
            kind = ConstantValueKind.Boolean,
            value = value,
            setType = true,
        )

    private fun buildNullLiteral(): FirExpression =
        buildLiteralExpression(
            source = null,
            kind = ConstantValueKind.Null,
            value = null,
            setType = true,
        )


    private fun functionStubName(binding: WitRuntimeBinding, direction: BindingDirection): Name? {
        val signature = binding.signature ?: return null
        if (signature.isAsync || signature.usesStreams) return null
        val supported = when (signature.kind) {
            FunctionKind.CONSTRUCTOR,
            FunctionKind.METHOD,
            FunctionKind.STATIC -> true
            else -> false
        }
        if (!supported) return null
        val directionSegment = when (direction) {
            BindingDirection.IMPORT -> "Import"
            BindingDirection.EXPORT -> "Export"
        }
        return Name.identifier(
            "__wit${directionSegment}Fn_" + sanitizeIdentifier(binding.name),
        )
    }

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

    internal data class WorldMetadata(
        val packageId: String,
        val packageFqName: FqName,
        val runtimeWorld: WitRuntimeWorld,
        val worldSchema: WitWorldMetadata?,
        val interfaceBindings: Map<Name, InterfaceBindingMetadata>,
        val importBindings: Map<Name, BindingMetadata>,
        val exportBindings: Map<Name, BindingMetadata>,
        val resourceBindings: Map<Name, ResourceBindingMetadata>,
        val constructorHelpers: Map<Name, ConstructorHelperMetadata>,
        val driverCompanionClassId: ClassId,
        val driverClassId: ClassId,
        val bindCallableId: CallableId,
        val driverImportsClassId: ClassId?,
        val driverExportsClassId: ClassId?,
        val registerImportsCallableId: CallableId?,
        val registerExportsCallableId: CallableId?,
        val driverResourcesClassId: ClassId?,
        val registerResourcesCallableId: CallableId?,
    ) {
        fun findBindingByFunctionName(name: Name): BindingMetadata? {
            importBindings.values.firstOrNull { it.matchesFunctionName(name) }?.let { return it }
            return exportBindings.values.firstOrNull { it.matchesFunctionName(name) }
        }

        fun findResourceByFunctionName(name: Name): ResourceBindingMetadata? =
            resourceBindings.values.firstOrNull { it.hostFunctionName == name }

        fun findConstructorHelper(name: Name): ConstructorHelperMetadata? =
            constructorHelpers[name]
    }

    internal data class InterfaceBindingMetadata(
        val runtime: WitRuntimeInterface?,
        val schema: WitInterfaceMetadata?,
    )

    internal enum class BindingDirection {
        IMPORT,
        EXPORT,
    }

    internal data class BindingMetadata(
        val binding: WitRuntimeBinding,
        val direction: BindingDirection,
        val functionStubName: Name?,
        val hostFunctionName: Name?,
        val interfaceName: String?,
        val resourceName: String?,
    ) {
        fun matchesFunctionName(name: Name): Boolean {
            if (functionStubName == name) return true
            if (hostFunctionName == name) return true
            return false
        }
    }

    internal data class ResourceBindingMetadata(
        val runtimeInterface: WitRuntimeInterface,
        val resource: WitRuntimeResource,
        val hostFunctionName: Name,
    )

    internal data class ConstructorHelperMetadata(
        val functionName: Name,
        val constructor: WitRuntimeConstructor,
        val binding: BindingMetadata,
        val direction: BindingDirection,
    )

    private companion object {
        private val WORLD_NAME_PROPERTY: Name = Name.identifier("__witWorldName")
        private val RUNTIME_SLOT_PROPERTY: Name = Name.identifier("__witRuntime")
        private val DRIVER_OBJECT_NAME: Name = Name.identifier(WIT_DRIVER_OBJECT_SIMPLE_NAME)
        private val DRIVER_PACKAGE_ID_PROPERTY: Name = Name.identifier("packageId")
        private val DRIVER_WORLD_NAME_PROPERTY: Name = Name.identifier("worldName")
        private val BIND_FUNCTION_NAME: Name = Name.identifier(WIT_DRIVER_BIND_FUNCTION_NAME)
        private val REGISTER_IMPORTS_FUNCTION_NAME: Name = Name.identifier("registerImports")
        private val REGISTER_EXPORTS_FUNCTION_NAME: Name = Name.identifier("registerExports")
        private val REGISTER_RESOURCES_FUNCTION_NAME: Name = Name.identifier("registerResources")
        private val REGISTER_IMPORT_HANDLER_NAME: Name = Name.identifier("registerImportHandler")
        private val REGISTER_EXPORT_HANDLER_NAME: Name = Name.identifier("registerExportHandler")
        private val RUNTIME_PARAMETER_NAME: Name = Name.identifier("runtime")
        private val IMPLEMENTATION_PARAMETER_NAME: Name = Name.identifier("impl")
        private val IMPORTS_INTERFACE_NAME: Name = Name.identifier("Imports")
        private val EXPORTS_INTERFACE_NAME: Name = Name.identifier("Exports")
        private val RESOURCES_INTERFACE_NAME: Name = Name.identifier("Resources")
        private const val CONSTRUCTOR_HELPER_PREFIX: String = "__witConstruct_"
        private val BINDING_HANDLER_CLASS_ID: ClassId = ClassId(FqName("org.jetbrains.kotlin.wit.runtime"), Name.identifier("BindingHandler"))
        private val RESOURCE_FACTORY_CLASS_ID: ClassId = ClassId(FqName("org.jetbrains.kotlin.wit.runtime"), Name.identifier("ResourceFactory"))
        private val RESOURCE_TYPE_CLASS_ID: ClassId = ClassId(FqName("org.jetbrains.kotlin.wit.runtime"), Name.identifier("ResourceType"))
        private val OWN_HANDLE_CLASS_ID: ClassId = ClassId(FqName("org.jetbrains.kotlin.wit.runtime"), Name.identifier("OwnHandle"))
        private val RESOURCE_CLASS_ID: ClassId = ClassId(FqName("org.jetbrains.kotlin.wit.runtime"), Name.identifier("Resource"))
    }

    private fun interfacePropertyName(rawName: String): Name =
        Name.identifier("__witInterface_" + sanitizeIdentifier(rawName))

    private fun bindingPropertyName(prefix: String, rawName: String): Name =
        Name.identifier("__wit${prefix}_" + sanitizeIdentifier(rawName))

    private fun resourcePropertyName(interfaceName: String, resourceName: String): Name =
        Name.identifier("__witResource_" + sanitizeIdentifier(interfaceName) + "_" + sanitizeIdentifier(resourceName))

    private fun allocateConstructorHelperName(rawBase: String, usedNames: MutableSet<String>): Name {
        val sanitizedBase = sanitizeIdentifier(rawBase).ifEmpty { "resource" }
        var candidate = CONSTRUCTOR_HELPER_PREFIX + sanitizedBase
        var suffix = 1
        while (!usedNames.add(candidate)) {
            candidate = CONSTRUCTOR_HELPER_PREFIX + sanitizedBase + "_" + suffix++
        }
        return Name.identifier(candidate)
    }

    private fun resolveBindingHandlerType(): ConeKotlinType {
        val symbol = session.symbolProvider.getClassLikeSymbolByClassId(BINDING_HANDLER_CLASS_ID)
            ?: error("Unable to resolve BindingHandler type alias for WIT driver generation")
        if (symbol is FirTypeAliasSymbol) {
            @OptIn(SymbolInternals::class)
            return symbol.fir.expandedTypeRef.coneType
        }
        if (symbol is FirClassSymbol<*>) {
            return symbol.defaultType()
        }
        error("Unexpected symbol for BindingHandler: ${symbol::class}")
    }

    private fun resolveResourceFactoryType(): ConeKotlinType {
        val symbol = session.symbolProvider.getClassLikeSymbolByClassId(RESOURCE_FACTORY_CLASS_ID)
            ?: error("Unable to resolve ResourceFactory for WIT driver generation")
        if (symbol is FirTypeAliasSymbol) {
            @OptIn(SymbolInternals::class)
            return symbol.fir.expandedTypeRef.coneType
        }
        if (symbol is FirClassSymbol<*>) {
            return symbol.defaultType()
        }
        error("Unexpected symbol for ResourceFactory: ${symbol::class}")
    }

    private fun resolveOwnHandleResourceType(): ConeKotlinType {
        val ownHandleSymbol = session.symbolProvider.getClassLikeSymbolByClassId(OWN_HANDLE_CLASS_ID) as? FirClassSymbol<*>
            ?: return session.builtinTypes.anyType.coneType
        val resourceSymbol = session.symbolProvider.getClassLikeSymbolByClassId(RESOURCE_CLASS_ID) as? FirClassSymbol<*>
            ?: return session.builtinTypes.anyType.coneType
        val resourceType = resourceSymbol.defaultType()
        return ownHandleSymbol.classId.constructClassLikeType(arrayOf(resourceType), isMarkedNullable = false)
    }

    private fun sanitizeParameterName(label: String?, index: Int): String {
        val base = label?.takeIf { it.isNotBlank() } ?: "param$index"
        return sanitizeIdentifier(base)
    }

}

internal enum class WitBindingIssue {
    ASYNC_UNSUPPORTED,
    STREAM_UNSUPPORTED,
    UNKNOWN_KIND,
}

internal object WitWorldDeclarationKey : GeneratedDeclarationKey() {
    override fun toString(): String = "WitWorldDeclaration"
}

internal object WitWorldDriverCompanionKey : GeneratedDeclarationKey() {
    override fun toString(): String = "WitWorldDriverCompanion"
}

internal object WitWorldDriverClassKey : GeneratedDeclarationKey() {
    override fun toString(): String = "WitWorldDriver"
}

internal object WitWorldDriverResourcesKey : GeneratedDeclarationKey() {
    override fun toString(): String = "WitWorldDriverResources"
}

internal object WitWorldDriverImportsKey : GeneratedDeclarationKey() {
    override fun toString(): String = "WitWorldDriverImports"
}

internal object WitWorldDriverExportsKey : GeneratedDeclarationKey() {
    override fun toString(): String = "WitWorldDriverExports"
}

private fun packageToFqName(packageId: String): FqName {
    val withoutVersion = packageId.substringBefore('@')
    val segments = withoutVersion.replace(':', '.').split('.')
    val sanitized = buildList {
        add("wit")
        add("generated")
        segments.filter { it.isNotBlank() }.forEach { add(sanitizeIdentifier(it)) }
    }
    return FqName(sanitized.joinToString(separator = "."))
}

private fun sanitizeIdentifier(raw: String): String {
    if (raw.isEmpty()) return "_"
    val builder = StringBuilder(raw.length)
    raw.forEach { ch ->
        builder.append(
            when {
                ch == '_' -> '_'
                ch.isLetterOrDigit() -> ch
                else -> '_'
            },
        )
    }
    if (builder.isEmpty()) return "_"
    if (!builder.first().isLetter() && builder.first() != '_') {
        builder.insert(0, '_')
    }
    return builder.toString()
}

// TODO: Hoist these class IDs into a shared WitFirBuiltIns once the runtime surface is defined.
private val WIT_RUNTIME_PACKAGE_FQNAME = FqName("org.jetbrains.kotlin.wit.runtime")
private val WIT_WORLD_CLASS_ID = ClassId(WIT_RUNTIME_PACKAGE_FQNAME, Name.identifier("WitWorld"))
private val WIT_BINDING_CLASS_ID = ClassId(WIT_RUNTIME_PACKAGE_FQNAME, Name.identifier("WitBinding"))
private val WIT_BINDING_DIRECTION_CLASS_ID =
    ClassId(WIT_RUNTIME_PACKAGE_FQNAME, Name.identifier("WitBindingDirection"))
private val WIT_BINDING_KIND_CLASS_ID =
    ClassId(WIT_RUNTIME_PACKAGE_FQNAME, Name.identifier("WitBindingKind"))
private val WIT_RESOURCE_CLASS_ID = ClassId(WIT_RUNTIME_PACKAGE_FQNAME, Name.identifier("WitResource"))
private val WIT_CONSTRUCTOR_CLASS_ID =
    ClassId(WIT_RUNTIME_PACKAGE_FQNAME, Name.identifier("WitConstructor"))
private val WORLD_DRIVER_CLASS_ID = ClassId(WIT_RUNTIME_PACKAGE_FQNAME, Name.identifier("WorldDriver"))
private val COMPONENT_RUNTIME_CLASS_ID = ClassId(WIT_RUNTIME_PACKAGE_FQNAME, Name.identifier("ComponentRuntime"))

private object WitWorldClassMetadataKey : FirDeclarationDataKey()

private var FirRegularClass.witWorldMetadataInternal: WitFirDeclarationGenerator.WorldMetadata? by FirDeclarationDataRegistry.data(
    WitWorldClassMetadataKey,
)

internal var FirRegularClass.witWorldMetadata: WitFirDeclarationGenerator.WorldMetadata?
    get() = witWorldMetadataInternal
    set(value) {
        witWorldMetadataInternal = value
    }

private object WitWorldPropertyMetadataKey : FirDeclarationDataKey()

internal var FirProperty.witWorldMetadata: WitFirDeclarationGenerator.WorldMetadata? by FirDeclarationDataRegistry.data(WitWorldPropertyMetadataKey)

internal val FirPropertySymbol.witWorldMetadata: WitFirDeclarationGenerator.WorldMetadata? by FirDeclarationDataRegistry.symbolAccessor(WitWorldPropertyMetadataKey)

private object WitWorldFunctionMetadataKey : FirDeclarationDataKey()

internal var FirSimpleFunction.witWorldFunctionMetadata: WitFirDeclarationGenerator.WorldMetadata? by FirDeclarationDataRegistry.data(WitWorldFunctionMetadataKey)

private object WitInterfaceMetadataKey : FirDeclarationDataKey()

private var FirProperty.witInterfaceMetadata: WitInterfaceMetadata? by FirDeclarationDataRegistry.data(WitInterfaceMetadataKey)

internal val FirPropertySymbol.witInterfaceMetadata: WitInterfaceMetadata? by FirDeclarationDataRegistry.symbolAccessor(WitInterfaceMetadataKey)

private object WitRuntimeInterfaceMetadataKey : FirDeclarationDataKey()

private var FirProperty.witRuntimeInterface: WitRuntimeInterface? by FirDeclarationDataRegistry.data(WitRuntimeInterfaceMetadataKey)

internal val FirPropertySymbol.witRuntimeInterface: WitRuntimeInterface? by FirDeclarationDataRegistry.symbolAccessor(WitRuntimeInterfaceMetadataKey)

private object WitBindingMetadataKey : FirDeclarationDataKey()

internal var FirProperty.witBindingMetadata: WitFirDeclarationGenerator.BindingMetadata? by FirDeclarationDataRegistry.data(WitBindingMetadataKey)

internal val FirPropertySymbol.witBindingMetadata: WitFirDeclarationGenerator.BindingMetadata? by FirDeclarationDataRegistry.symbolAccessor(WitBindingMetadataKey)

private object WitResourceMetadataKey : FirDeclarationDataKey()

private var FirProperty.witResourceMetadata: WitFirDeclarationGenerator.ResourceBindingMetadata? by FirDeclarationDataRegistry.data(WitResourceMetadataKey)

internal val FirPropertySymbol.witResourceMetadata: WitFirDeclarationGenerator.ResourceBindingMetadata? by FirDeclarationDataRegistry.symbolAccessor(WitResourceMetadataKey)

private object WitBindingIssuesKey : FirDeclarationDataKey()

private var FirProperty.witBindingIssuesOrNull: Set<WitBindingIssue>? by FirDeclarationDataRegistry.data(WitBindingIssuesKey)

internal var FirProperty.witBindingIssues: Set<WitBindingIssue>
    get() = witBindingIssuesOrNull ?: emptySet()
    set(value) {
        witBindingIssuesOrNull = if (value.isEmpty()) null else value
    }

private val FirPropertySymbol.witBindingIssuesOrNull: Set<WitBindingIssue>? by FirDeclarationDataRegistry.symbolAccessor(
    WitBindingIssuesKey,
)

internal val FirPropertySymbol.witBindingIssues: Set<WitBindingIssue>
    get() = witBindingIssuesOrNull ?: emptySet()

private object WitConstructorMetadataKey : FirDeclarationDataKey()

private var FirConstructor.witConstructorMetadata: WitRuntimeConstructor? by FirDeclarationDataRegistry.data(WitConstructorMetadataKey)

internal val FirConstructorSymbol.witConstructorMetadata: WitRuntimeConstructor? by FirDeclarationDataRegistry.symbolAccessor(WitConstructorMetadataKey)
