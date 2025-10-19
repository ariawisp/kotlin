@file:OptIn(
    org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class,
    org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi::class,
)

package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.createExpressionBody
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private const val OFFSET = -1
private val REGISTRY_PACKAGE = FqName("wit.generated")
private const val REGISTRY_FILE_NAME = "__witGeneratedRegistry.kt"
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
        registryFile.declarations += createInitializerField(registryFile, drivers)
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

    private fun createInitializerField(
        file: IrFile,
        drivers: List<WitIrPlan.Driver>,
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

        val builder = DeclarationIrBuilder(pluginContext, field.symbol)
        val registerWorldsSymbol = symbols.generatedModuleRegistryRegisterWorlds
        val vararg = builder.irVararg(
            symbols.worldDriverType,
            drivers.map { driver -> builder.irGetObject(driver.driverClass.symbol) },
        )
        val expression = builder.irCall(registerWorldsSymbol).apply {
            dispatchReceiver = builder.irGetObject(symbols.generatedModuleRegistryClass)
            putValueArgument(0, vararg)
        }
        field.initializer = pluginContext.irFactory.createExpressionBody(expression)

        return field
    }
}
