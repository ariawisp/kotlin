@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrThrowImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.Name

internal const val SYNTHETIC_OFFSET: Int = UNDEFINED_OFFSET

internal class WasmIrBuilderContext(
    private val pluginContext: IrPluginContext,
    private val symbols: WasmRuntimeSymbols,
) {
    fun stringConst(value: String): IrExpression =
        IrConstImpl.string(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, symbols.stringType, value)

    fun booleanConst(value: Boolean): IrExpression =
        IrConstImpl.boolean(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, symbols.booleanType, value)

    fun nullConst(type: IrType): IrExpression =
        IrConstImpl.constNull(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, type)

    fun enumEntry(members: List<*>, name: String): IrExpression {
        val entry = members.filterIsInstance<org.jetbrains.kotlin.ir.declarations.IrEnumEntry>().firstOrNull { it.name.asString() == name }
            ?: error("Enum entry $name not found")
        return IrGetEnumValueImpl(
            SYNTHETIC_OFFSET,
            SYNTHETIC_OFFSET,
            entry.parentAsClass.defaultType,
            entry.symbol,
        )
    }

    fun createValueParameter(owner: IrFunction, index: Int, name: String, type: IrType): IrValueParameter =
        pluginContext.irFactory.buildValueParameter(
            org.jetbrains.kotlin.ir.builders.declarations.IrValueParameterBuilder().apply {
                this.name = Name.identifier(name)
                this.type = type
                startOffset = SYNTHETIC_OFFSET
                endOffset = SYNTHETIC_OFFSET
            },
            owner,
        )

    fun notImplementedThrow(message: String): IrThrowImpl {
        val call: IrCallImpl = org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl.Companion.fromSymbolOwner(
            SYNTHETIC_OFFSET,
            SYNTHETIC_OFFSET,
            symbols.notImplementedError,
        )
        call.type = symbols.notImplementedError.owner.returnType
        call.arguments[0] = stringConst(message)
        return IrThrowImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, symbols.nothingType, call)
    }

    val builtIns get() = pluginContext.irBuiltIns
    val runtimeSymbols get() = symbols
    val irPluginContext get() = pluginContext
}
