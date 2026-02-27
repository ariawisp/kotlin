package org.jetbrains.kotlin.wit.compiler.fir

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.extensions.FirStatusTransformerExtension

internal class WitFirStatusTransformer(
    session: FirSession,
) : FirStatusTransformerExtension(session) {
    override fun needTransformStatus(declaration: FirDeclaration): Boolean = false
}
