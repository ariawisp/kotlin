@file:OptIn(
    org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class,
    org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi::class,
)

package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irUnit
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.createExpressionBody
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrExpressionBodyImpl
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private const val OFFSET = -1
private val REGISTRY_PACKAGE = FqName("wit.generated")
private const val REGISTRY_FILE_NAME = "__witGeneratedRegistry.kt"
private const val REGISTRY_FUNCTION_NAME = "registerAllGeneratedWorlds"
private const val REGISTRY_INITIALIZER_NAME = "__witGeneratedModuleInitializer"

internal class WitIrGeneratedRegistryEmitter(
    private val pluginContext: IrPluginContext,
    private val plan: WitIrPlan,
) {
    private val symbols = WasmRuntimeSymbols(pluginContext)

    fun emit(module: IrModuleFragment) {
        val drivers = plan.worlds.mapNotNull { it.driver }
        if (drivers.isEmpty()) return

        val registryFile = obtainRegistryFile(module)
        val registerFunction = createRegisterFunction(registryFile, drivers)
        registryFile.declarations += registerFunction
        registryFile.declarations += createInitializerField(registryFile, registerFunction)
    }

    private fun obtainRegistryFile(module: IrModuleFragment): IrFile {
        return module.files.firstOrNull { it.packageFqName == REGISTRY_PACKAGE }
            ?: IrFileImpl(
                NaiveSourceBasedFileEntryImpl(
                    REGISTRY_PACKAGE.asString().replace('.', '/') + "/$REGISTRY_FILE_NAME"
                ),
                org.jetbrains.kotlin.ir.symbols.impl.IrFileSymbolImpl(),
                REGISTRY_PACKAGE,
                module
            ).also { module.files += it }
    }

    private fun createRegisterFunction(
        file: IrFile,
        drivers: List<WitIrPlan.Driver>,
    ): IrSimpleFunction {
        val function = pluginContext.irFactory.buildFun {
            startOffset = OFFSET
            endOffset = OFFSET
            origin = WitIrGeneratedOrigin
            name = Name.identifier(REGISTRY_FUNCTION_NAME)
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.FINAL
            returnType = pluginContext.irBuiltIns.unitType
            isInline = false
            isExternal = false
            isTailrec = false
            isSuspend = false
            isOperator = false
            isExpect = false
        }.apply {
            parent = file
        }

        val runtimeParameter = function.addValueParameter("runtime", symbols.componentRuntimeType)

        val registerDriverSymbol = symbols.componentRuntimeRegisterDriver
        val builder = DeclarationIrBuilder(pluginContext, function.symbol)
        function.body = builder.irBlockBody {
            drivers.forEach { driver ->
                +irCall(registerDriverSymbol).apply {
                    dispatchReceiver = irGet(runtimeParameter)
                    putValueArgument(0, irGetObject(driver.driverClass.symbol))
                }
            }
            +irReturn(irUnit())
        }

        return function
    }

    private fun createInitializerField(
        file: IrFile,
        registerFunction: IrSimpleFunction,
    ): IrField {
        val field = pluginContext.irFactory.buildField {
            startOffset = OFFSET
            endOffset = OFFSET
            origin = WitIrGeneratedOrigin
            name = Name.identifier(REGISTRY_INITIALIZER_NAME)
            type = pluginContext.irBuiltIns.unitType
            visibility = DescriptorVisibilities.PRIVATE
            isFinal = true
            isExternal = false
            isStatic = true
        }.apply {
            parent = file
        }

        val functionType = pluginContext.irBuiltIns.functionN(1)
            .typeWith(symbols.componentRuntimeType, pluginContext.irBuiltIns.unitType)

        val lambdaFunction = pluginContext.irFactory.buildFun {
            startOffset = OFFSET
            endOffset = OFFSET
            origin = org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
            name = Name.special("<anonymous>")
            returnType = pluginContext.irBuiltIns.unitType
            visibility = DescriptorVisibilities.LOCAL
        }.apply {
            parent = file
            val runtimeParameter = addValueParameter("runtime", symbols.componentRuntimeType)
            body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
                +irCall(registerFunction.symbol).apply {
                    putValueArgument(0, irGet(runtimeParameter))
                }
                +irReturn(irUnit())
            }
        }

        val lambdaExpression = IrFunctionExpressionImpl(
            OFFSET,
            OFFSET,
            functionType,
            lambdaFunction,
            IrStatementOrigin.LAMBDA,
        )

        val builder = DeclarationIrBuilder(pluginContext, field.symbol)
        val registerModuleRegistrarSymbol = symbols.generatedModuleRegistryRegister
        val expression = builder.irCall(registerModuleRegistrarSymbol).apply {
            dispatchReceiver = builder.irGetObject(symbols.generatedModuleRegistryClass)
            putValueArgument(0, lambdaExpression)
        }
        field.initializer = pluginContext.irFactory.createExpressionBody(expression)

        return field
    }
}
