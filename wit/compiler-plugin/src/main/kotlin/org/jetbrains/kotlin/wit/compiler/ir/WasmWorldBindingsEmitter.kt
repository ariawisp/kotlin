package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addBackingField
import org.jetbrains.kotlin.ir.builders.declarations.addDefaultGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.wit.codegen.core.plan.PackagePlan
import org.jetbrains.kotlin.wit.codegen.core.plan.WorldPlan

internal class WasmWorldBindingsEmitter(
    private val context: WasmIrBuilderContext,
) {
    private val pluginContext = context.irPluginContext
    private val symbols = context.runtimeSymbols

    fun emit(worldClass: IrClass, pkg: PackagePlan, world: WorldPlan) {
        declareBindingDelegates(worldClass, pkg, world)
        declareResourceProperties(worldClass, world)
        declareBindingFunctions(worldClass, world)
    }

    private fun declareBindingDelegates(worldClass: IrClass, pkg: PackagePlan, world: WorldPlan) {
        buildBindingEntries(world).forEach { binding ->
            val property = worldClass.addProperty {
                name = Name.identifier(binding.propertyName)
                modality = Modality.FINAL
                visibility = DescriptorVisibilities.PUBLIC
                isVar = false
                origin = IrDeclarationOrigin.GENERATED_BY_PLUGIN
                startOffset = SYNTHETIC_OFFSET
                endOffset = SYNTHETIC_OFFSET
            }
            val field = property.addBackingField {
                type = symbols.bindingDelegateType
                origin = IrDeclarationOrigin.PROPERTY_BACKING_FIELD
                isFinal = true
            }
            field.initializer = pluginContext.irFactory.createExpressionBody(
                createPendingBindingDelegate(pkg.id, world.name, binding),
            )
            property.addDefaultGetter(worldClass, pluginContext.irBuiltIns)
            property.annotations = property.annotations + createBindingAnnotation(binding)
        }
    }

    private fun declareResourceProperties(worldClass: IrClass, world: WorldPlan) {
        buildResourceEntries(world).forEach { resource ->
            val property = worldClass.addProperty {
                name = Name.identifier(resource.propertyName)
                modality = Modality.FINAL
                visibility = DescriptorVisibilities.PUBLIC
                isVar = false
                origin = IrDeclarationOrigin.GENERATED_BY_PLUGIN
                startOffset = SYNTHETIC_OFFSET
                endOffset = SYNTHETIC_OFFSET
            }
            val field = property.addBackingField {
                type = symbols.anyNullableType
                origin = IrDeclarationOrigin.PROPERTY_BACKING_FIELD
                isFinal = true
            }
            field.initializer = pluginContext.irFactory.createExpressionBody(
                context.nullConst(symbols.anyNullableType),
            )
            property.addDefaultGetter(worldClass, pluginContext.irBuiltIns)
            property.annotations = property.annotations + createResourceAnnotation(resource)
        }
    }

    private fun declareBindingFunctions(worldClass: IrClass, world: WorldPlan) {
        buildBindingEntries(world)
            .filter { it.signature != null }
            .forEach { binding ->
                val signature = binding.signature!!
                val function = pluginContext.irFactory.buildFun {
                    name = Name.identifier(binding.functionName!!)
                    modality = Modality.ABSTRACT
                    visibility = DescriptorVisibilities.PUBLIC
                    origin = IrDeclarationOrigin.GENERATED_BY_PLUGIN
                    startOffset = SYNTHETIC_OFFSET
                    endOffset = SYNTHETIC_OFFSET
                    returnType = if (signature.results.isEmpty()) symbols.unitType else symbols.anyNullableType
                }.apply {
                    parent = worldClass
                    val regularParameters = signature.parameters.mapIndexed { index, param ->
                        context.createValueParameter(
                            owner = this,
                            index = index,
                            name = sanitizeIdentifier(param.label ?: "param$index"),
                            type = symbols.anyNullableType,
                        )
                    }
                    this.parameters = listOf(createDispatchReceiverParameterWithClassParent()) + regularParameters
                    annotations = annotations + createBindingAnnotation(binding)
                }
                worldClass.declarations += function
            }
    }

    private fun createPendingBindingDelegate(
        packageId: String,
        worldName: String,
        binding: WasmBindingEntry,
    ): IrExpression = IrCallImpl(
        SYNTHETIC_OFFSET,
        SYNTHETIC_OFFSET,
        symbols.bindingDelegateType,
        symbols.pendingBindingDelegate,
        typeArgumentsCount = 0,
        valueArgumentsCount = symbols.pendingBindingDelegate.owner.valueParameters.size,
    ).apply {
        (0 until symbols.pendingBindingDelegate.owner.valueParameters.size).forEach { putValueArgument(it, null) }
        putValueArgument(0, context.stringConst(packageId))
        putValueArgument(1, context.stringConst(worldName))
        putValueArgument(2, context.stringConst(binding.bindingName))
        putValueArgument(3, context.enumEntry(symbols.bindingDirectionEnum.owner.declarations, binding.direction.name))
        putValueArgument(4, context.enumEntry(symbols.bindingKindEnum.owner.declarations, binding.kind.name))
        putValueArgument(5, context.stringConst(binding.runtimeTarget))
        putValueArgument(6, context.booleanConst(binding.isAsync))
        putValueArgument(7, context.booleanConst(binding.usesStreams))
    }

    private fun createBindingAnnotation(binding: WasmBindingEntry): IrConstructorCallImpl =
        IrConstructorCallImpl.fromSymbolOwner(
            SYNTHETIC_OFFSET,
            SYNTHETIC_OFFSET,
            symbols.witBindingAnnotation.defaultType,
            symbols.witBindingConstructor.symbol,
        ).apply {
            putValueArgument(0, context.enumEntry(symbols.bindingDirectionEnum.owner.declarations, binding.direction.name))
            putValueArgument(1, context.enumEntry(symbols.bindingKindEnum.owner.declarations, binding.kind.name))
            putValueArgument(2, context.stringConst(binding.interfaceName))
            putValueArgument(3, context.stringConst(binding.resourceName))
            putValueArgument(4, context.stringConst(binding.bindingName))
            putValueArgument(5, context.stringConst(binding.runtimeTarget))
            putValueArgument(6, context.booleanConst(binding.isAsync))
            putValueArgument(7, context.booleanConst(binding.usesStreams))
        }

    private fun createResourceAnnotation(resource: WasmResourceEntry): IrConstructorCallImpl =
        IrConstructorCallImpl.fromSymbolOwner(
            SYNTHETIC_OFFSET,
            SYNTHETIC_OFFSET,
            symbols.witResourceAnnotation.defaultType,
            symbols.witResourceConstructor.symbol,
        ).apply {
            putValueArgument(0, context.stringConst(resource.interfaceName))
            putValueArgument(1, context.stringConst(resource.resourceName))
            putValueArgument(2, context.stringConst(resource.ownHandleType))
            putValueArgument(3, context.stringConst(resource.borrowHandleType))
        }
}
