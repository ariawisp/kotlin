package org.jetbrains.kotlin.wit.compiler.fir

import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeAliasSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.name.CallableId

internal class WitResourceAdapterBuilder(
    private val extension: FirDeclarationGenerationExtension,
    private val session: FirSession,
    private val annotationBuilder: WitFirAnnotationBuilder,
    private val worldClassBuilder: WitWorldClassBuilder,
) {

    private val resourceFactoryType: ConeKotlinType by lazy { resolveResourceFactoryType() }
    private val ownHandleType: ConeKotlinType by lazy { resolveOwnHandleResourceType() }

    fun generateDriverResourceFunctions(
        callableId: CallableId,
        ownerSymbol: FirRegularClassSymbol,
        metadata: WorldMetadata,
    ): List<FirNamedFunctionSymbol> {
        val resourceMetadata = metadata.findResourceByFunctionName(callableId.callableName) ?: return emptyList()
        val function = extension.createMemberFunction(
            ownerSymbol,
            WitWorldDriverResourcesKey,
            callableId.callableName,
            resourceFactoryType,
        ) {
            worldClassBuilder.applyMemberSource(this, ownerSymbol)
            modality = Modality.ABSTRACT
            visibility = Visibilities.Public
        }

        annotationBuilder.buildResourceAnnotation(resourceMetadata)?.let { annotation ->
            function.replaceAnnotations(function.annotations + annotation)
        }
        function.witWorldFunctionMetadata = metadata

        return listOf(function.symbol)
    }

    fun ownHandleResourceType(): ConeKotlinType = ownHandleType

    fun resourceFactoryType(): ConeKotlinType = resourceFactoryType

    private fun resolveResourceFactoryType(): ConeKotlinType {
        val symbol = session.symbolProvider.getClassLikeSymbolByClassId(RESOURCE_FACTORY_CLASS_ID)
            ?: error("Unable to resolve ResourceFactory for WIT driver generation")
        return when (symbol) {
            is FirTypeAliasSymbol -> {
                @OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)
                symbol.fir.expandedTypeRef.coneType
            }
            is FirClassSymbol<*> -> symbol.defaultType()
        }
    }

    private fun resolveOwnHandleResourceType(): ConeKotlinType {
        val ownHandleSymbol = session.symbolProvider.getClassLikeSymbolByClassId(OWN_HANDLE_CLASS_ID) as? FirClassSymbol<*>
            ?: return session.builtinTypes.anyType.coneType
        val resourceSymbol = session.symbolProvider.getClassLikeSymbolByClassId(RESOURCE_CLASS_ID) as? FirClassSymbol<*>
            ?: return session.builtinTypes.anyType.coneType
        val resourceType = resourceSymbol.defaultType()
        return ownHandleSymbol.classId.constructClassLikeType(arrayOf(resourceType), isMarkedNullable = false)
    }
}
