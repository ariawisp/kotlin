package org.jetbrains.kotlin.wit.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

internal const val WIT_PLUGIN_ID: String = "org.jetbrains.kotlin.wit.compiler"

@OptIn(ExperimentalCompilerApi::class)
public class WitCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = WIT_PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption>
        get() = WitOption.entries.map { it.cliOption }

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        when (WitOption.byName[option.optionName]) {
            WitOption.ENABLED -> configuration.setEnabled(value.toBooleanStrict())
            WitOption.DEBUG -> configuration.setDebug(value.toBooleanStrict())
            WitOption.ROOT -> configuration.addRoot(value)
            WitOption.INCLUDE -> configuration.addInclude(value)
            WitOption.FEATURE -> configuration.addFeature(value)
            WitOption.JSON -> configuration.addJsonSchema(value)
            null -> throw CliOptionProcessingException("Unknown option: ${option.optionName}")
        }
    }
}
