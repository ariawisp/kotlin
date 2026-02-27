package org.jetbrains.kotlin.wit.compiler.fir

import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.ClassId

internal class WitBindingStubBuilder(
    private val extension: FirDeclarationGenerationExtension,
    private val session: FirSession,
    private val annotationBuilder: WitFirAnnotationBuilder,
    private val worldClassBuilder: WitWorldClassBuilder,
    private val ownHandleTypeProvider: () -> ConeKotlinType,
    private val resourceFactoryTypeProvider: () -> ConeKotlinType,
) {

    fun generateFunctions(
        callableId: CallableId,
        context: MemberGenerationContext?,
        pluginOrigin: FirDeclarationOrigin.Plugin,
        metadata: WorldMetadata,
    ): List<FirNamedFunctionSymbol> {
        val ownerSymbol = context?.owner as? FirRegularClassSymbol ?: return emptyList()
        return when (pluginOrigin.key) {
            WitWorldDriverCompanionKey -> {
                metadata.findConstructorHelper(callableId.callableName)?.let { helper ->
                    generateConstructorHelperFunction(ownerSymbol, metadata, helper)?.let(::listOf) ?: emptyList()
                } ?: generateDriverCompanionFunctions(callableId, ownerSymbol, metadata)
            }
            WitWorldDriverImportsKey -> generateDriverContractFunctions(callableId, ownerSymbol, metadata, BindingDirection.IMPORT)
            WitWorldDriverExportsKey -> generateDriverContractFunctions(callableId, ownerSymbol, metadata, BindingDirection.EXPORT)
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

        val function = extension.createMemberFunction(ownerSymbol, key, callableId.callableName, returnType) {
            worldClassBuilder.applyMemberSource(this, ownerSymbol)
            modality = Modality.ABSTRACT
            visibility = Visibilities.Public
            signature.parameters.forEachIndexed { index, parameter ->
                val parameterName = Name.identifier(sanitizeParameterName(parameter.label, index))
                valueParameter(parameterName, session.builtinTypes.anyType.coneType)
            }
        }

        annotationBuilder.buildBindingAnnotation(bindingMetadata)?.let { annotation ->
            function.replaceAnnotations(function.annotations + annotation)
        }
        function.witWorldFunctionMetadata = metadata

        return listOf(function.symbol)
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

        val function = extension.createMemberFunction(ownerSymbol, WitWorldDeclarationKey, callableId.callableName, returnType) {
            worldClassBuilder.applyMemberSource(this, ownerSymbol)
            modality = Modality.ABSTRACT
            visibility = Visibilities.Public
            signature.parameters.forEachIndexed { index, parameter ->
                val parameterName = Name.identifier(sanitizeParameterName(parameter.label, index))
                valueParameter(parameterName, session.builtinTypes.anyType.coneType)
            }
        }

        annotationBuilder.buildBindingAnnotation(bindingMetadata)?.let { annotation ->
            function.replaceAnnotations(function.annotations + annotation)
        }
        function.witWorldFunctionMetadata = metadata

        return listOf(function.symbol)
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
                val function = extension.createMemberFunction(
                    ownerSymbol,
                    WitWorldDriverCompanionKey,
                    BIND_FUNCTION_NAME,
                    session.builtinTypes.unitType.coneType,
                ) {
                    worldClassBuilder.applyMemberSource(this, ownerSymbol)
                    modality = Modality.FINAL
                    visibility = Visibilities.Public
                    status { isOverride = false }
                    valueParameter(RUNTIME_PARAMETER_NAME, componentRuntimeSymbol.defaultType())
                }
                function.witWorldFunctionMetadata = metadata
                listOf(function.symbol)
            }
            REGISTER_IMPORTS_FUNCTION_NAME -> buildRegisterFunction(
                ownerSymbol,
                componentRuntimeSymbol,
                metadata,
                REGISTER_IMPORTS_FUNCTION_NAME,
                metadata.driverImportsClassId,
            )
            REGISTER_EXPORTS_FUNCTION_NAME -> buildRegisterFunction(
                ownerSymbol,
                componentRuntimeSymbol,
                metadata,
                REGISTER_EXPORTS_FUNCTION_NAME,
                metadata.driverExportsClassId,
            )
            REGISTER_RESOURCES_FUNCTION_NAME -> buildRegisterResourcesFunction(
                ownerSymbol,
                componentRuntimeSymbol,
                metadata,
            )
            else -> emptyList()
        }
    }

    private fun buildRegisterFunction(
        ownerSymbol: FirRegularClassSymbol,
        componentRuntimeSymbol: FirClassSymbol<*>,
        metadata: WorldMetadata,
        name: Name,
        contractClassId: ClassId?,
    ): List<FirNamedFunctionSymbol> {
        val contractClassId = contractClassId ?: return emptyList()
        val contractSymbol =
            session.symbolProvider.getClassLikeSymbolByClassId(contractClassId) as? FirClassSymbol<*>
                ?: return emptyList()
        val function = extension.createMemberFunction(
            ownerSymbol,
            WitWorldDriverCompanionKey,
            name,
            session.builtinTypes.unitType.coneType,
        ) {
            worldClassBuilder.applyMemberSource(this, ownerSymbol)
            modality = Modality.FINAL
            visibility = Visibilities.Public
            status { isOverride = false }
            valueParameter(RUNTIME_PARAMETER_NAME, componentRuntimeSymbol.defaultType())
            valueParameter(IMPLEMENTATION_PARAMETER_NAME, contractSymbol.defaultType())
        }
        function.witWorldFunctionMetadata = metadata
        return listOf(function.symbol)
    }

    private fun buildRegisterResourcesFunction(
        ownerSymbol: FirRegularClassSymbol,
        componentRuntimeSymbol: FirClassSymbol<*>,
        metadata: WorldMetadata,
    ): List<FirNamedFunctionSymbol> {
        val contractClassId = metadata.driverResourcesClassId ?: return emptyList()
        val contractSymbol =
            session.symbolProvider.getClassLikeSymbolByClassId(contractClassId) as? FirClassSymbol<*>
                ?: return emptyList()

        val function = extension.createMemberFunction(
            ownerSymbol,
            WitWorldDriverCompanionKey,
            REGISTER_RESOURCES_FUNCTION_NAME,
            session.builtinTypes.unitType.coneType,
        ) {
            worldClassBuilder.applyMemberSource(this, ownerSymbol)
            modality = Modality.FINAL
            visibility = Visibilities.Public
            status { isOverride = false }
            valueParameter(RUNTIME_PARAMETER_NAME, componentRuntimeSymbol.defaultType())
            valueParameter(IMPLEMENTATION_PARAMETER_NAME, contractSymbol.defaultType())
        }
        function.witWorldFunctionMetadata = metadata
        return listOf(function.symbol)
    }

    fun generateConstructorHelperFunction(
        ownerSymbol: FirRegularClassSymbol,
        metadata: WorldMetadata,
        helper: ConstructorHelperMetadata,
    ): FirNamedFunctionSymbol? {
        val componentRuntimeSymbol =
            session.symbolProvider.getClassLikeSymbolByClassId(COMPONENT_RUNTIME_CLASS_ID) as? FirClassSymbol<*>
                ?: return null
        val resourceFactoryType = resourceFactoryTypeProvider()
        val ownHandleType = ownHandleTypeProvider()

        val function = extension.createMemberFunction(
            ownerSymbol,
            WitWorldDriverCompanionKey,
            helper.functionName,
            ownHandleType,
        ) {
            worldClassBuilder.applyMemberSource(this, ownerSymbol)
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
        annotationBuilder.buildConstructorAnnotation(metadata, helper.constructor)?.let { annotation ->
            function.replaceAnnotations(function.annotations + annotation)
        }
        return function.symbol
    }
}
