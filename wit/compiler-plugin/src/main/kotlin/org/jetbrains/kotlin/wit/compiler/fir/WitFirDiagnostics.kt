package org.jetbrains.kotlin.wit.compiler.fir

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.diagnostics.AbstractSourceElementPositioningStrategy
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies

internal object KtErrorsWit : KtDiagnosticsContainer() {
    val WIT_MULTI_CONSTRUCTOR_WORLD by error1WithContainer<PsiElement, String>()
    val WIT_ASYNC_BINDING_UNSUPPORTED by error1WithContainer<PsiElement, String>()
    val WIT_STREAM_BINDING_UNSUPPORTED by error1WithContainer<PsiElement, String>()
    val WIT_UNKNOWN_BINDING_KIND by error1WithContainer<PsiElement, String>()

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = DefaultErrorMessagesWit
}

private object DefaultErrorMessagesWit : BaseDiagnosticRendererFactory() {
    override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("WitPlugin") { map ->
        map.put(KtErrorsWit.WIT_MULTI_CONSTRUCTOR_WORLD, "{0}", CommonRenderers.STRING)
        map.put(KtErrorsWit.WIT_ASYNC_BINDING_UNSUPPORTED, "{0}", CommonRenderers.STRING)
        map.put(KtErrorsWit.WIT_STREAM_BINDING_UNSUPPORTED, "{0}", CommonRenderers.STRING)
        map.put(KtErrorsWit.WIT_UNKNOWN_BINDING_KIND, "{0}", CommonRenderers.STRING)
    }
}

private inline fun <reified P : PsiElement, A> KtDiagnosticsContainer.error1WithContainer(
    positioningStrategy: AbstractSourceElementPositioningStrategy = SourceElementPositioningStrategies.DEFAULT,
) = with(this) {
    error1<P, A>(positioningStrategy)
}
