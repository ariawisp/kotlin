package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addDefaultGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.wit.codegen.core.plan.PackagePlan
import org.jetbrains.kotlin.wit.codegen.core.plan.WorldPlan

internal class WasmWorldClassBuilder(
    private val context: WasmIrBuilderContext,
) {
    private val pluginContext = context.irPluginContext
    private val symbols = context.runtimeSymbols

    fun build(file: IrFile, pkg: PackagePlan, world: WorldPlan): IrClass {
        val worldClass = pluginContext.irFactory.buildClass {
            name = Name.identifier(sanitizeIdentifier(world.name))
            kind = ClassKind.CLASS
            modality = Modality.ABSTRACT
            visibility = DescriptorVisibilities.PUBLIC
            origin = IrDeclarationOrigin.GENERATED_BY_PLUGIN
            startOffset = SYNTHETIC_OFFSET
            endOffset = SYNTHETIC_OFFSET
        }.apply {
            parent = file
            superTypes = mutableListOf(pluginContext.irBuiltIns.anyType)
            thisReceiver = buildReceiverParameter { type = pluginContext.irBuiltIns.anyType }
            annotations += IrConstructorCallImpl.fromSymbolOwner(
                SYNTHETIC_OFFSET,
                SYNTHETIC_OFFSET,
                symbols.witWorldAnnotation.defaultType,
                symbols.witWorldConstructor.symbol,
            ).apply {
                putValueArgument(0, context.stringConst(pkg.id))
                putValueArgument(1, context.stringConst(world.name))
            }
        }

        declareWorldNameProperty(worldClass, world.name)
        declareInterfaceProperties(worldClass, world)

        return worldClass
    }

    private fun declareWorldNameProperty(worldClass: IrClass, worldName: String) {
        val property = worldClass.addProperty {
            name = Name.identifier(WORLD_NAME_PROPERTY)
            modality = Modality.FINAL
            visibility = DescriptorVisibilities.PUBLIC
            isVar = false
            origin = IrDeclarationOrigin.GENERATED_BY_PLUGIN
            startOffset = SYNTHETIC_OFFSET
            endOffset = SYNTHETIC_OFFSET
        }
        val getter = property.addDefaultGetter(worldClass, pluginContext.irBuiltIns).apply {
            dispatchReceiverParameter = worldClass.thisReceiver!!.copyTo(this, type = worldClass.thisReceiver!!.type)
            returnType = symbols.stringType
            body = pluginContext.irFactory.createBlockBody(
                SYNTHETIC_OFFSET,
                SYNTHETIC_OFFSET,
                listOf(
                    IrReturnImpl(
                        SYNTHETIC_OFFSET,
                        SYNTHETIC_OFFSET,
                        pluginContext.irBuiltIns.nothingType,
                        symbol,
                        context.stringConst(worldName),
                    ),
                ),
            )
        }
        getter.correspondingPropertySymbol = property.symbol
    }

    private fun declareInterfaceProperties(worldClass: IrClass, world: WorldPlan) {
        val interfaces = linkedSetOf<String>()
        world.imports.forEach { (it.target as? org.jetbrains.kotlin.wit.codegen.core.plan.BindingTargetPlan.Interface)?.name?.let(interfaces::add) }
        world.exports.forEach { (it.target as? org.jetbrains.kotlin.wit.codegen.core.plan.BindingTargetPlan.Interface)?.name?.let(interfaces::add) }

        interfaces.forEach { interfaceName ->
            val property = worldClass.addProperty {
                name = Name.identifier(interfacePropertyName(interfaceName))
                modality = Modality.FINAL
                visibility = DescriptorVisibilities.PUBLIC
                isVar = false
                origin = IrDeclarationOrigin.GENERATED_BY_PLUGIN
                startOffset = SYNTHETIC_OFFSET
                endOffset = SYNTHETIC_OFFSET
            }
            val getter = property.addDefaultGetter(worldClass, pluginContext.irBuiltIns).apply {
                dispatchReceiverParameter = worldClass.thisReceiver!!.copyTo(this, type = worldClass.thisReceiver!!.type)
                returnType = symbols.stringType
                body = pluginContext.irFactory.createBlockBody(
                    SYNTHETIC_OFFSET,
                    SYNTHETIC_OFFSET,
                    listOf(
                        IrReturnImpl(
                            SYNTHETIC_OFFSET,
                            SYNTHETIC_OFFSET,
                            pluginContext.irBuiltIns.nothingType,
                            symbol,
                            context.stringConst(interfaceName),
                        ),
                    ),
                )
            }
            getter.correspondingPropertySymbol = property.symbol
        }
    }

    private companion object {
        private const val WORLD_NAME_PROPERTY = "__witWorldName"
    }
}
