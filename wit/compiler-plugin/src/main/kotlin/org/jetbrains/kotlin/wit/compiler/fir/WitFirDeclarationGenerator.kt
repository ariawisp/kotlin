package org.jetbrains.kotlin.wit.compiler.fir

import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.builder.buildValueParameter
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.DeclarationGenerationContext
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.plugin.DeclarationBuildingContext
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.deserialization.toQualifiedPropertyAccessExpression
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.types.withNullability
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.wit.compiler.WitPluginOptions
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeConstructor
import org.jetbrains.kotlin.wit.compiler.schema.WitSchemaIndex
import org.jetbrains.kotlin.wit.compiler.schema.WitWorldMetadata
import org.jetbrains.kotlin.wit.compiler.fir.annotate.WitFirAnnotationBuilder
import org.jetbrains.kotlin.wit.compiler.fir.builders.WitWorldClassBuilder
import org.jetbrains.kotlin.wit.compiler.fir.builders.WitBindingStubBuilder
import org.jetbrains.kotlin.wit.compiler.fir.builders.WitResourceAdapterBuilder
import org.jetbrains.kotlin.wit.compiler.fir.map.WitFirSchemaMapper
import org.jetbrains.kotlin.wit.compiler.fir.names.sanitizeParameterName

@OptIn(ExperimentalTopLevelDeclarationsGenerationApi::class)
internal class WitFirDeclarationGenerator(
    session: FirSession,
    private val schemaIndex: WitSchemaIndex,
    private val options: WitPluginOptions,
) : FirDeclarationGenerationExtension(session) {

    private val worldDeclarations: Map<ClassId, WorldMetadata>
    private val packages: Set<FqName>
    private val classSourceCache = mutableMapOf<ClassId, KtSourceElement>()
    private val annotationBuilder = WitFirAnnotationBuilder(session)
    private val worldClassBuilder = WitWorldClassBuilder(session, annotationBuilder, classSourceCache)
    private val resourceAdapterBuilder = WitResourceAdapterBuilder(session, annotationBuilder, worldClassBuilder)
    private val bindingStubBuilder = WitBindingStubBuilder(
        session,
        annotationBuilder,
        worldClassBuilder,
        { resourceAdapterBuilder.ownHandleResourceType() },
        { resourceAdapterBuilder.resourceFactoryType() },
    )
    private val topLevelClassIds: Set<ClassId>

    init {
        val mapping = WitFirSchemaMapper(schemaIndex).map()
        worldDeclarations = mapping.worldDeclarations
        packages = mapping.packages
        topLevelClassIds = mapping.topLevelClassIds

        if (options.debug) {
            println(renderDebugTrace(worldDeclarations))
        }
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
        return worldClassBuilder.generateTopLevelClass(classId, metadata)
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
        return worldClassBuilder.getNestedClassifiersNames(classSymbol, metadata, pluginOrigin)
    }

    override fun generateNestedClassLikeDeclaration(
        owner: FirClassSymbol<*>,
        name: Name,
        context: DeclarationGenerationContext.Nested,
    ): FirClassLikeSymbol<*>? {
        val pluginOrigin = owner.origin as? FirDeclarationOrigin.Plugin ?: return null
        val metadata = worldDeclarations[owner.classId] ?: return null
        return worldClassBuilder.generateNestedClassLikeDeclaration(owner, name, metadata, pluginOrigin)
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
        return worldClassBuilder.getCallableNamesForClass(pluginOrigin.key, metadata)
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
            WitWorldDeclarationKey -> worldClassBuilder.generateWorldProperties(ownerSymbol, metadata, callableName)
            WitWorldDriverCompanionKey -> worldClassBuilder.generateCompanionProperties(ownerSymbol, metadata, callableName)
            else -> emptyList()
        }
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
            WitWorldDriverResourcesKey -> resourceAdapterBuilder.generateDriverResourceFunctions(callableId, ownerSymbol, metadata)
            else -> bindingStubBuilder.generateFunctions(callableId, context, pluginOrigin, metadata)
        }
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
                worldClassBuilder.applyMemberSource(this, owner)
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
            annotationBuilder.buildConstructorAnnotation(metadata, runtimeConstructor)?.let { annotation ->
                constructor.replaceAnnotations(constructor.annotations + annotation)
            }
            constructor.symbol
        }
    }
