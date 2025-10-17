package org.jetbrains.kotlin.wit.compiler.fir

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.resolvePhase
import org.jetbrains.kotlin.wit.compiler.schema.FunctionKind

internal class WitFirCheckers(session: org.jetbrains.kotlin.fir.FirSession) : FirAdditionalCheckersExtension(session) {
    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val regularClassCheckers: Set<FirRegularClassChecker> = setOf(WitWorldClassChecker)
        override val propertyCheckers: Set<FirPropertyChecker> = setOf(WitBindingPropertyChecker)
    }
}

private object WitWorldClassChecker : FirRegularClassChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirRegularClass) {
        if (!declaration.isPluginGeneratedWorld()) return
        val metadata = declaration.witWorldMetadata ?: return
        val constructorCount = metadata.runtimeWorld.constructors.size
        if (constructorCount <= 1) return
        val source = declaration.source ?: return

        val message = buildString {
            append("WIT world '")
            append(metadata.packageId)
            append('/')
            append(metadata.runtimeWorld.name)
            append("' declares ")
            append(constructorCount)
            append(" constructors; only a single constructor is supported.")
        }
        with(context) {
            reporter.reportOn(source, KtErrorsWit.WIT_MULTI_CONSTRUCTOR_WORLD, message)
        }
    }
}

private object WitBindingPropertyChecker : FirPropertyChecker(MppCheckerKind.Common) {
    private val supportedFunctionKinds = setOf(
        FunctionKind.FUNCTION,
        FunctionKind.METHOD,
        FunctionKind.STATIC,
        FunctionKind.CONSTRUCTOR,
    )

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirProperty) {
        if (!declaration.isPluginGeneratedWorld()) return
        if (declaration.resolvePhase < FirResolvePhase.BODY_RESOLVE) return
        val worldMetadata = declaration.witWorldMetadata ?: return
        val bindingMetadata = declaration.witBindingMetadata ?: return
        val source = declaration.source ?: return
        val binding = bindingMetadata.binding
        val issues = mutableSetOf<WitBindingIssue>()

        val qualifiedWorldName = "${worldMetadata.packageId}/${worldMetadata.runtimeWorld.name}"
        val fallbackName = declaration.symbol.callableId?.callableName?.asString()
        val bindingName = binding.name.ifBlank { fallbackName ?: binding.name }

        if (binding.signature?.isAsync == true) {
            with(context) {
                reporter.reportOn(
                    source,
                    KtErrorsWit.WIT_ASYNC_BINDING_UNSUPPORTED,
                    "Async WIT binding '$bindingName' in world '$qualifiedWorldName' is not supported yet.",
                )
            }
            issues += WitBindingIssue.ASYNC_UNSUPPORTED
        }

        if (binding.signature?.usesStreams == true) {
            with(context) {
                reporter.reportOn(
                    source,
                    KtErrorsWit.WIT_STREAM_BINDING_UNSUPPORTED,
                    "Streaming WIT binding '$bindingName' in world '$qualifiedWorldName' is not supported yet.",
                )
            }
            issues += WitBindingIssue.STREAM_UNSUPPORTED
        }

        val signatureKind = binding.signature?.kind
        if (signatureKind != null && signatureKind !in supportedFunctionKinds) {
            with(context) {
                reporter.reportOn(
                    source,
                    KtErrorsWit.WIT_UNKNOWN_BINDING_KIND,
                    "WIT binding '$bindingName' in world '$qualifiedWorldName' uses unsupported function kind '$signatureKind'.",
                )
            }
            issues += WitBindingIssue.UNKNOWN_KIND
        }

        if (issues.isNotEmpty()) {
            declaration.witBindingIssues = issues
        }
    }
}

private fun FirRegularClass.isPluginGeneratedWorld(): Boolean {
    val pluginOrigin = origin as? FirDeclarationOrigin.Plugin ?: return false
    return pluginOrigin.key == WitWorldDeclarationKey
}

private fun FirProperty.isPluginGeneratedWorld(): Boolean {
    val pluginOrigin = origin as? FirDeclarationOrigin.Plugin ?: return false
    return pluginOrigin.key == WitWorldDeclarationKey
}
