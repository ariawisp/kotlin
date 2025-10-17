package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.types.createType
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.util.parentClassOrNull

/**
 * Ensures that companion objects nested under generated world classes report a nested
 * dispatch receiver type rather than the synthetic top-level `Companion`. This keeps
 * the original IR identical to the copy produced by `deepCopyWithSymbols`.
 */
@OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)
internal class WitIrCompanionNormalizationLowering(
    @Suppress("UNUSED_PARAMETER")
    private val pluginContext: IrPluginContext,
) {
    fun apply(moduleFragment: IrModuleFragment) {
        moduleFragment.files.forEach { file ->
            file.declarations.filterIsInstance<IrClass>().forEach(::normalize)
        }
    }

    private fun normalize(irClass: IrClass) {
        if (irClass.name.asString() == "Companion") {
            irClass.parentClassOrNull?.let { parent ->
                val companionType = irClass.symbol.createType(false, emptyList()).makeNotNull()
                val receiver = irClass.thisReceiver
                if (receiver != null && receiver.type != companionType) {
                    receiver.type = companionType
                }
            }
        }
        irClass.declarations.filterIsInstance<IrClass>().forEach(::normalize)
    }
}
