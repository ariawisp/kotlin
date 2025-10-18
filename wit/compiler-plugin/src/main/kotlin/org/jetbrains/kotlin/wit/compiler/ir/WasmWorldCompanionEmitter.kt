package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.addBackingField
import org.jetbrains.kotlin.ir.builders.declarations.addDefaultGetter
import org.jetbrains.kotlin.ir.builders.declarations.addDefaultSetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.wit.codegen.core.plan.PackagePlan
import org.jetbrains.kotlin.wit.codegen.core.plan.WorldPlan
import org.jetbrains.kotlin.wit.compiler.WIT_DRIVER_BIND_FUNCTION_NAME
import org.jetbrains.kotlin.wit.compiler.WIT_DRIVER_OBJECT_SIMPLE_NAME
import org.jetbrains.kotlin.wit.model.BindingKind
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner

internal class WasmWorldCompanionEmitter(
    private val context: WasmIrBuilderContext,
) {
    private val pluginContext = context.irPluginContext
    private val symbols = context.runtimeSymbols
    private val driverEmitter = WasmWorldDriverEmitter(context)

    fun emit(worldClass: IrClass, pkg: PackagePlan, world: WorldPlan) {
        val companion = pluginContext.irFactory.buildClass {
            name = SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT
            kind = org.jetbrains.kotlin.descriptors.ClassKind.OBJECT
            modality = org.jetbrains.kotlin.descriptors.Modality.FINAL
            visibility = org.jetbrains.kotlin.descriptors.DescriptorVisibilities.PUBLIC
            origin = IrDeclarationOrigin.GENERATED_BY_PLUGIN
            startOffset = SYNTHETIC_OFFSET
            endOffset = SYNTHETIC_OFFSET
            isCompanion = true
        }.apply {
            parent = worldClass
            superTypes = mutableListOf(symbols.builtIns.anyType)
            thisReceiver = buildReceiverParameter { type = symbols.builtIns.anyType }
        }
        worldClass.declarations += companion

        declareRuntimeSlot(companion)
        declareCompanionApi(companion, world)
        declareConstructorHelpers(companion, world)
        driverEmitter.emit(companion, pkg, world)
    }

    private fun declareRuntimeSlot(companion: IrClass) {
        val runtimeType = symbols.componentRuntimeType.makeNullable()
        val property = companion.addProperty {
            name = Name.identifier(RUNTIME_SLOT_PROPERTY)
            modality = org.jetbrains.kotlin.descriptors.Modality.FINAL
            visibility = org.jetbrains.kotlin.descriptors.DescriptorVisibilities.PRIVATE
            isVar = true
            origin = IrDeclarationOrigin.GENERATED_BY_PLUGIN
            startOffset = SYNTHETIC_OFFSET
            endOffset = SYNTHETIC_OFFSET
        }
        val field = property.addBackingField {
            type = runtimeType
            origin = IrDeclarationOrigin.PROPERTY_BACKING_FIELD
            isFinal = false
        }
        field.initializer = pluginContext.irFactory.createExpressionBody(context.nullConst(runtimeType))
        property.addDefaultGetter(companion, pluginContext.irBuiltIns)
        property.getter!!.dispatchReceiverParameter = companion.thisReceiver!!.copyTo(property.getter!!, type = companion.thisReceiver!!.type)
        property.getter!!.returnType = runtimeType

        property.addDefaultSetter(companion, pluginContext.irBuiltIns)
        property.setter!!.dispatchReceiverParameter = companion.thisReceiver!!.copyTo(property.setter!!, type = companion.thisReceiver!!.type)
    }

    private fun declareCompanionApi(companion: IrClass, world: WorldPlan) {
        fun addFunction(name: String, parameters: List<Pair<String, IrType>>, returnType: IrType = symbols.unitType) {
            val function = pluginContext.irFactory.buildFun {
                this.name = Name.identifier(name)
                modality = org.jetbrains.kotlin.descriptors.Modality.FINAL
                visibility = org.jetbrains.kotlin.descriptors.DescriptorVisibilities.PUBLIC
                origin = IrDeclarationOrigin.GENERATED_BY_PLUGIN
                startOffset = SYNTHETIC_OFFSET
                endOffset = SYNTHETIC_OFFSET
                this.returnType = returnType
            }.apply {
                parent = companion
                valueParameters = parameters.mapIndexed { index, (paramName, paramType) ->
                    context.createValueParameter(this, index, paramName, paramType)
                }
                body = pluginContext.irFactory.createBlockBody(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET).apply {
                    statements += context.notImplementedThrow("$name is not implemented yet")
                }
            }
            companion.declarations += function
        }

        addFunction(WIT_DRIVER_BIND_FUNCTION_NAME, listOf("runtime" to symbols.componentRuntimeType))
        if (world.imports.isNotEmpty()) {
            addFunction(
                REGISTER_IMPORTS_FUNCTION_NAME,
                listOf("runtime" to symbols.componentRuntimeType, "impl" to symbols.anyNullableType),
            )
        }
        if (world.exports.isNotEmpty()) {
            addFunction(
                REGISTER_EXPORTS_FUNCTION_NAME,
                listOf("runtime" to symbols.componentRuntimeType, "impl" to symbols.anyNullableType),
            )
        }
        if ((world.imports + world.exports).any { it.kind == BindingKind.RESOURCE }) {
            addFunction(
                REGISTER_RESOURCES_FUNCTION_NAME,
                listOf("runtime" to symbols.componentRuntimeType, "impl" to symbols.anyNullableType),
            )
        }
    }

    private fun declareConstructorHelpers(companion: IrClass, world: WorldPlan) {
        world.constructors.forEach { constructor ->
            val helperName = constructorHelperName(constructor.bindingName)
            val function = pluginContext.irFactory.buildFun {
                name = Name.identifier(helperName)
                modality = org.jetbrains.kotlin.descriptors.Modality.FINAL
                visibility = org.jetbrains.kotlin.descriptors.DescriptorVisibilities.PUBLIC
                origin = IrDeclarationOrigin.GENERATED_BY_PLUGIN
                startOffset = SYNTHETIC_OFFSET
                endOffset = SYNTHETIC_OFFSET
                returnType = symbols.anyNullableType
            }.apply {
                parent = companion
                val params = mutableListOf<IrValueParameter>()
                params += context.createValueParameter(this, params.size, "runtime", symbols.componentRuntimeType)
                params += context.createValueParameter(this, params.size, "factory", symbols.resourceFactoryType)
                constructor.signature.parameters.forEachIndexed { index, param ->
                    params += context.createValueParameter(
                        this,
                        params.size,
                        sanitizeIdentifier(param.label ?: "param$index"),
                        symbols.anyNullableType,
                    )
                }
                valueParameters = params
                body = pluginContext.irFactory.createBlockBody(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET).apply {
                    statements += context.notImplementedThrow("$helperName is not implemented yet")
                }
                annotations = annotations + IrConstructorCallImpl.fromSymbolOwner(
                    SYNTHETIC_OFFSET,
                    SYNTHETIC_OFFSET,
                    symbols.witConstructorAnnotation.defaultType,
                    symbols.witConstructorConstructor.symbol,
                ).apply {
                    putValueArgument(0, context.stringConst(constructor.bindingName))
                    putValueArgument(1, context.enumEntry(symbols.bindingDirectionEnum.owner.declarations, WasmBindingDirection.EXPORT.name))
                }
            }
            companion.declarations += function
        }
    }

    private companion object {
        private const val SYNTHETIC_OFFSET = UNDEFINED_OFFSET
        private const val RUNTIME_SLOT_PROPERTY = "__witRuntime"
        private const val REGISTER_IMPORTS_FUNCTION_NAME = "registerImports"
        private const val REGISTER_EXPORTS_FUNCTION_NAME = "registerExports"
        private const val REGISTER_RESOURCES_FUNCTION_NAME = "registerResources"
    }
}

private class WasmWorldDriverEmitter(
    private val context: WasmIrBuilderContext,
) {
    private val pluginContext = context.irPluginContext
    private val symbols = context.runtimeSymbols

    fun emit(companion: IrClass, pkg: PackagePlan, world: WorldPlan) {
        val driver = pluginContext.irFactory.buildClass {
            name = Name.identifier(WIT_DRIVER_OBJECT_SIMPLE_NAME)
            kind = org.jetbrains.kotlin.descriptors.ClassKind.OBJECT
            modality = org.jetbrains.kotlin.descriptors.Modality.FINAL
            visibility = org.jetbrains.kotlin.descriptors.DescriptorVisibilities.PUBLIC
            origin = IrDeclarationOrigin.GENERATED_BY_PLUGIN
            startOffset = SYNTHETIC_OFFSET
            endOffset = SYNTHETIC_OFFSET
        }.apply {
            parent = companion
            superTypes = mutableListOf(symbols.worldDriverType)
            thisReceiver = buildReceiverParameter { type = symbols.builtIns.anyType }
        }
        companion.declarations += driver

        addOverrideProperty(driver, symbols.worldDriverProperties.getValue("packageId")) { context.stringConst(pkg.id) }
        addOverrideProperty(driver, symbols.worldDriverProperties.getValue("worldName")) { context.stringConst(world.name) }

        addOverrideFunction(driver, symbols.worldDriverFunctions.getValue(WIT_DRIVER_BIND_FUNCTION_NAME)) { function ->
            listOf(context.createValueParameter(function, 0, "runtime", symbols.componentRuntimeType))
        }

        addDriverHelper(driver, REGISTER_IMPORT_HANDLER_NAME) { function ->
            listOf(
                context.createValueParameter(function, 0, "runtime", symbols.componentRuntimeType),
                context.createValueParameter(function, 1, "bindingName", symbols.stringType),
                context.createValueParameter(function, 2, "handler", symbols.bindingHandlerType),
            )
        }
        addDriverHelper(driver, REGISTER_EXPORT_HANDLER_NAME) { function ->
            listOf(
                context.createValueParameter(function, 0, "runtime", symbols.componentRuntimeType),
                context.createValueParameter(function, 1, "bindingName", symbols.stringType),
                context.createValueParameter(function, 2, "handler", symbols.bindingHandlerType),
            )
        }
    }

    private fun addOverrideProperty(
        owner: IrClass,
        base: org.jetbrains.kotlin.ir.declarations.IrProperty,
        valueProvider: () -> IrExpression,
    ) {
        val property = owner.addProperty {
            name = base.name
            modality = org.jetbrains.kotlin.descriptors.Modality.FINAL
            visibility = org.jetbrains.kotlin.descriptors.DescriptorVisibilities.PUBLIC
            isVar = false
            origin = IrDeclarationOrigin.GENERATED_BY_PLUGIN
            startOffset = SYNTHETIC_OFFSET
            endOffset = SYNTHETIC_OFFSET
        }
        property.overriddenSymbols = listOf(base.symbol)
        property.addDefaultGetter(owner, pluginContext.irBuiltIns)
        property.getter!!.dispatchReceiverParameter = owner.thisReceiver!!.copyTo(property.getter!!, type = owner.thisReceiver!!.type)
        property.getter!!.returnType = base.getter?.returnType ?: symbols.stringType
        property.getter!!.overriddenSymbols = listOfNotNull(base.getter?.symbol)
        property.getter!!.body = pluginContext.irFactory.createBlockBody(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET).apply {
            statements += IrReturnImpl(
                SYNTHETIC_OFFSET,
                SYNTHETIC_OFFSET,
                pluginContext.irBuiltIns.nothingType,
                property.getter!!.symbol,
                valueProvider(),
            )
        }
    }

    private fun addOverrideFunction(
        owner: IrClass,
        base: org.jetbrains.kotlin.ir.declarations.IrSimpleFunction,
        parameterFactory: (IrFunction) -> List<IrValueParameter>,
    ) {
        val function = pluginContext.irFactory.buildFun {
            name = base.name
            modality = org.jetbrains.kotlin.descriptors.Modality.FINAL
            visibility = org.jetbrains.kotlin.descriptors.DescriptorVisibilities.PUBLIC
            origin = IrDeclarationOrigin.GENERATED_BY_PLUGIN
            startOffset = SYNTHETIC_OFFSET
            endOffset = SYNTHETIC_OFFSET
            returnType = base.returnType
        }.apply {
            parent = owner
            dispatchReceiverParameter = owner.thisReceiver!!.copyTo(this, type = owner.thisReceiver!!.type)
            valueParameters = parameterFactory(this)
            overriddenSymbols = listOf(base.symbol)
            body = pluginContext.irFactory.createBlockBody(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET).apply {
                statements += context.notImplementedThrow("${base.name.asString()} is not implemented yet")
            }
        }
        owner.declarations += function
    }

    private fun addDriverHelper(
        owner: IrClass,
        name: String,
        parameterFactory: (IrFunction) -> List<IrValueParameter>,
    ) {
        val function = pluginContext.irFactory.buildFun {
            this.name = Name.identifier(name)
            modality = org.jetbrains.kotlin.descriptors.Modality.FINAL
            visibility = org.jetbrains.kotlin.descriptors.DescriptorVisibilities.PUBLIC
            origin = IrDeclarationOrigin.GENERATED_BY_PLUGIN
            startOffset = SYNTHETIC_OFFSET
            endOffset = SYNTHETIC_OFFSET
            returnType = symbols.unitType
        }.apply {
            parent = owner
            dispatchReceiverParameter = owner.thisReceiver!!.copyTo(this, type = owner.thisReceiver!!.type)
            valueParameters = parameterFactory(this)
            body = pluginContext.irFactory.createBlockBody(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET).apply {
                statements += context.notImplementedThrow("$name is not implemented yet")
            }
        }
        owner.declarations += function
    }

    private companion object {
        private const val SYNTHETIC_OFFSET = UNDEFINED_OFFSET
        private const val REGISTER_IMPORT_HANDLER_NAME = "registerImportHandler"
        private const val REGISTER_EXPORT_HANDLER_NAME = "registerExportHandler"
    }
}
