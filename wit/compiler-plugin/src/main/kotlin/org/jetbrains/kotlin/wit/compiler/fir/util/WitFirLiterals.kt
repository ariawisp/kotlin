package org.jetbrains.kotlin.wit.compiler.fir

import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildLiteralExpression
import org.jetbrains.kotlin.types.ConstantValueKind

internal fun buildStringLiteral(value: String): FirExpression =
    buildLiteralExpression(
        source = null,
        kind = ConstantValueKind.String,
        value = value,
        setType = true,
    )

internal fun buildBooleanLiteral(value: Boolean): FirExpression =
    buildLiteralExpression(
        source = null,
        kind = ConstantValueKind.Boolean,
        value = value,
        setType = true,
    )

internal fun buildNullLiteral(): FirExpression =
    buildLiteralExpression(
        source = null,
        kind = ConstantValueKind.Null,
        value = null,
        setType = true,
    )
