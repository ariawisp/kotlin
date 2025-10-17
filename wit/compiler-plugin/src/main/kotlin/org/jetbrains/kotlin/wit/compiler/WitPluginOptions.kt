package org.jetbrains.kotlin.wit.compiler

import java.nio.file.Path
import java.nio.file.Paths
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

internal object WitPluginConfigurationKeys {
    val ENABLED: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("wit.enabled")
    val DEBUG: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("wit.debug")
    val ROOTS: CompilerConfigurationKey<MutableList<String>> =
        CompilerConfigurationKey.create("wit.roots")
    val INCLUDES: CompilerConfigurationKey<MutableList<String>> =
        CompilerConfigurationKey.create("wit.includes")
    val FEATURES: CompilerConfigurationKey<MutableList<String>> =
        CompilerConfigurationKey.create("wit.features")
    val JSON_SCHEMAS: CompilerConfigurationKey<MutableList<String>> =
        CompilerConfigurationKey.create("wit.json")
}

internal enum class WitOption(val cliOption: CliOption) {
    ENABLED(
        CliOption(
            optionName = "enabled",
            valueDescription = "<true | false>",
            description = "Enable or disable the WIT compiler plugin for this compilation.",
            required = false,
            allowMultipleOccurrences = false,
        )
    ),
    DEBUG(
        CliOption(
            optionName = "debug",
            valueDescription = "<true | false>",
            description = "Enable debug logging for the WIT compiler plugin.",
            required = false,
            allowMultipleOccurrences = false,
        )
    ),
    ROOT(
        CliOption(
            optionName = "root",
            valueDescription = "<path>",
            description = "Path to a root WIT package or directory. May be specified multiple times.",
            required = false,
            allowMultipleOccurrences = true,
        )
    ),
    INCLUDE(
        CliOption(
            optionName = "include",
            valueDescription = "<path>",
            description = "Additional search directory used when resolving WIT dependencies.",
            required = false,
            allowMultipleOccurrences = true,
        )
    ),
    FEATURE(
        CliOption(
            optionName = "feature",
            valueDescription = "<name>",
            description = "Enable an optional WIT feature (e.g. async, resources). May be repeated.",
            required = false,
            allowMultipleOccurrences = true,
        )
    ),
    JSON(
        CliOption(
            optionName = "json",
            valueDescription = "<path>",
            description = "Path to precompiled WIT JSON metadata. May be specified multiple times.",
            required = false,
            allowMultipleOccurrences = true,
        )
    ),
    ;

    companion object {
        val byName: Map<String, WitOption> = entries.associateBy { it.cliOption.optionName }
    }
}

data class WitPluginOptions(
    val enabled: Boolean,
    val debug: Boolean,
    val rootPaths: List<Path>,
    val includePaths: List<Path>,
    val features: Set<String>,
    val jsonSchemas: List<Path>,
) {
    companion object {
        fun load(configuration: CompilerConfiguration): WitPluginOptions {
            val enabled = configuration[WitPluginConfigurationKeys.ENABLED] ?: true
            val debug = configuration[WitPluginConfigurationKeys.DEBUG] ?: false
            return WitPluginOptions(
                enabled = enabled,
                debug = debug,
                rootPaths = configuration[WitPluginConfigurationKeys.ROOTS].orEmpty().map(::asPath),
                includePaths = configuration[WitPluginConfigurationKeys.INCLUDES].orEmpty().map(::asPath),
                features = configuration[WitPluginConfigurationKeys.FEATURES].orEmpty().toSet(),
                jsonSchemas = configuration[WitPluginConfigurationKeys.JSON_SCHEMAS].orEmpty().map(::asPath),
            )
        }

        private fun asPath(raw: String): Path = Paths.get(raw)

        private fun <T> MutableList<T>?.orEmpty(): List<T> = this?.toList() ?: emptyList()
    }
}

fun CompilerConfiguration.setEnabled(value: Boolean) {
    put(WitPluginConfigurationKeys.ENABLED, value)
}

fun CompilerConfiguration.setDebug(value: Boolean) {
    put(WitPluginConfigurationKeys.DEBUG, value)
}

fun CompilerConfiguration.addRoot(value: String) {
    configurationListFor(WitPluginConfigurationKeys.ROOTS).add(value)
}

fun CompilerConfiguration.addInclude(value: String) {
    configurationListFor(WitPluginConfigurationKeys.INCLUDES).add(value)
}

fun CompilerConfiguration.addFeature(value: String) {
    configurationListFor(WitPluginConfigurationKeys.FEATURES).add(value)
}

fun CompilerConfiguration.addJsonSchema(value: String) {
    configurationListFor(WitPluginConfigurationKeys.JSON_SCHEMAS).add(value)
}

private fun <T> CompilerConfiguration.configurationListFor(
    key: CompilerConfigurationKey<MutableList<T>>,
): MutableList<T> {
    val current = this[key]
    if (current != null) {
        return current
    }
    val list = mutableListOf<T>()
    put(key, list)
    return list
}
