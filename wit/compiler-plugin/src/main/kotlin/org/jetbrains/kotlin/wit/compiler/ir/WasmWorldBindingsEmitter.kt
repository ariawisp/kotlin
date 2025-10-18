@file:OptIn(
    org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi::class,
    org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class,
)

package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addBackingField
import org.jetbrains.kotlin.ir.builders.declarations.addDefaultGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.createExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.defaultType
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
                origin = WitIrGeneratedOrigin
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
                origin = WitIrGeneratedOrigin
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
                    origin = WitIrGeneratedOrigin
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
    ): IrExpression {
        val call: IrCallImpl = org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl.Companion.fromSymbolOwner(
            SYNTHETIC_OFFSET,
            SYNTHETIC_OFFSET,
            symbols.pendingBindingDelegate,
        )
        call.type = symbols.bindingDelegateType
        call.arguments[0] = context.stringConst(packageId)
        call.arguments[1] = context.stringConst(worldName)
        call.arguments[2] = context.stringConst(binding.bindingName)
        call.arguments[3] = context.enumEntry(symbols.bindingDirectionEnum.owner.declarations, binding.direction.name)
        call.arguments[4] = context.enumEntry(symbols.bindingKindEnum.owner.declarations, binding.kind.name)
        call.arguments[5] = context.stringConst(binding.runtimeTarget)
        call.arguments[6] = context.booleanConst(binding.isAsync)
        call.arguments[7] = context.booleanConst(binding.usesStreams)
        return call
    }

    private fun createBindingAnnotation(binding: WasmBindingEntry): IrConstructorCallImpl {
        val annotation = org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl.Companion.fromSymbolOwner(
            SYNTHETIC_OFFSET,
            SYNTHETIC_OFFSET,
            symbols.witBindingAnnotation.owner.defaultType,
            symbols.witBindingConstructor.symbol,
        )
        annotation.arguments[0] = context.enumEntry(symbols.bindingDirectionEnum.owner.declarations, binding.direction.name)
        annotation.arguments[1] = context.enumEntry(symbols.bindingKindEnum.owner.declarations, binding.kind.name)
        annotation.arguments[2] = context.stringConst(binding.interfaceName)
        annotation.arguments[3] = context.stringConst(binding.resourceName)
        annotation.arguments[4] = context.stringConst(binding.bindingName)
        annotation.arguments[5] = context.stringConst(binding.runtimeTarget)
        annotation.arguments[6] = context.booleanConst(binding.isAsync)
        annotation.arguments[7] = context.booleanConst(binding.usesStreams)
        return annotation
    }

    private fun createResourceAnnotation(resource: WasmResourceEntry): IrConstructorCallImpl {
        val annotation = org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl.Companion.fromSymbolOwner(
            SYNTHETIC_OFFSET,
            SYNTHETIC_OFFSET,
            symbols.witResourceAnnotation.owner.defaultType,
            symbols.witResourceConstructor.symbol,
        )
        annotation.arguments[0] = context.stringConst(resource.interfaceName)
        annotation.arguments[1] = context.stringConst(resource.resourceName)
        annotation.arguments[2] = context.stringConst(resource.ownHandleType)
        annotation.arguments[3] = context.stringConst(resource.borrowHandleType)
        return annotation
    }
}
