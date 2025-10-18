package org.jetbrains.kotlin.wit.compiler.fir

import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirStatusTransformerExtension
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.wit.compiler.WitPluginOptions
import org.jetbrains.kotlin.wit.compiler.schema.WitSchemaIndex

class WitFirExtensionRegistrar(
    private val options: WitPluginOptions,
    private val schemaIndex: WitSchemaIndex,
) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        if (options.debug) {
            println(
                buildString {
                    append("WIT FIR registrar initialized with ")
                    append(schemaIndex.witPackages.size)
                    append(" package(s) and ")
                    append(schemaIndex.jsonSchemas.size)
                    append(" precompiled JSON schema(s)")
                    if (schemaIndex.packageMetadata.isNotEmpty()) {
                        append(" [packages=")
                        append(schemaIndex.packageMetadata.joinToString { it.name })
                        append(']')
                    }
                },
            )
        }
        +FirDeclarationGenerationExtension.Factory { session ->
            WitFirDeclarationGenerator(session, schemaIndex, options)
        }
        +FirSupertypeGenerationExtension.Factory { session ->
            WitFirSupertypeExtension(session, schemaIndex)
        }
        +FirStatusTransformerExtension.Factory { session ->
            WitFirStatusTransformer(session)
        }
        // Temporarily disable additional checkers and diagnostics to avoid PSI dependency in CLI
        // +FirAdditionalCheckersExtension.Factory { session ->
        //     WitFirCheckers(session)
        // }
        // registerDiagnosticContainers(KtErrorsWit)

        // TODO: register diagnostics and builtins once WIT type modeling is in place.
    }
}
