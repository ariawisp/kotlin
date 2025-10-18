package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin

private object WitIrGeneratedDeclarationKey : GeneratedDeclarationKey() {
    override fun toString(): String = "WitIrGenerated"
}

internal val WitIrGeneratedOrigin: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(WitIrGeneratedDeclarationKey)
