package org.jetbrains.kotlin.wit.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.wit.compiler.fir.WitFirExtensionRegistrar
import org.jetbrains.kotlin.wit.compiler.driver.WitBindingGenerationPipeline
import org.jetbrains.kotlin.wit.compiler.ir.WitIrGenerationExtension
import org.jetbrains.kotlin.wit.compiler.toSchemaConfig

@OptIn(ExperimentalCompilerApi::class)
public class WitCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String
        get() = WIT_PLUGIN_ID

    override val supportsK2: Boolean
        get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val options = WitPluginOptions.load(configuration)
        if (!options.enabled) return

        val messageCollector = configuration.messageCollector
        if (options.debug) {
            println("Registering WIT compiler plugin with options: $options")
        }

        val schemaIndex = WitBindingGenerationPipeline.loadSchema(
            options.toSchemaConfig(),
            messageCollector,
        ) ?: return

        if (options.debug) {
            println(
                "WIT FIR registrar initialized with ${schemaIndex.witPackages.size} package(s) and ${schemaIndex.jsonSchemas.size} precompiled JSON schema(s)"
            )
        }

        // If nothing was loaded, do not register FIR/IR hooks to avoid tripping declaration checkers
        if (schemaIndex.witPackages.isEmpty() && schemaIndex.jsonSchemas.isEmpty()) {
            if (options.debug) println("WIT FIR trace: <empty>")
            return
        }

        FirExtensionRegistrarAdapter.registerExtension(
            WitFirExtensionRegistrar(options, schemaIndex),
        )

        IrGenerationExtension.registerExtension(
            WitIrGenerationExtension(options.debug, schemaIndex),
        )
    }
}

private val CompilerConfiguration.messageCollector: MessageCollector
    get() = get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
