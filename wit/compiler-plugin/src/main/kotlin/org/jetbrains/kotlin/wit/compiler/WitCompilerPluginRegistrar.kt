package org.jetbrains.kotlin.wit.compiler

import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.LinkedHashSet
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.wit.compiler.driver.WitBindingGenerationPipeline
import org.jetbrains.kotlin.wit.compiler.fir.WitFirExtensionRegistrar
import org.jetbrains.kotlin.wit.compiler.ir.WitIrGenerationExtension
import org.jetbrains.kotlin.wit.compiler.toSchemaConfig

@OptIn(ExperimentalCompilerApi::class)
public class WitCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String
        get() = WIT_PLUGIN_ID

    override val supportsK2: Boolean
        get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val moduleName = configuration.moduleName
        val baseOptions = WitPluginOptions.load(configuration)
        val messageCollector = configuration.messageCollector
        val componentEnabled = configuration.getBooleanKey("org.jetbrains.kotlin.wasm.config.WasmConfigurationKeys", "WASM_COMPONENT_ENABLED")
        val pluginRequired = componentEnabled == true

        val autoRoots = configuration.autoSchemaRootsViaReflection(messageCollector)
        val mergedRoots = mergePaths(baseOptions.rootPaths, autoRoots)

        val options = baseOptions.copy(
            enabled = baseOptions.enabled || pluginRequired,
            rootPaths = mergedRoots,
        )

        if (!options.enabled) {
            if (pluginRequired) {
                messageCollector.report(
                    CompilerMessageSeverity.ERROR,
                    "Module '$moduleName' targets wasm-wasi component mode but the WIT compiler plugin is disabled.",
                )
            }
            return
        }

        if (options.debug) {
            println("Registering WIT compiler plugin with options: $options (autoRoots=$autoRoots)")
        }

        val hasSchemaInputs = options.rootPaths.isNotEmpty() || options.jsonSchemas.isNotEmpty()
        if (!hasSchemaInputs) {
            if (pluginRequired) {
                messageCollector.report(
                    CompilerMessageSeverity.ERROR,
                    "Module '$moduleName' targets wasm-wasi component mode but no WIT schema inputs were configured. Set -Xwit=<path> or plugin:wit.root options.",
                )
            } else if (options.debug) {
                println("WIT FIR trace: <skipped – no schema inputs>")
            }
            return
        }

        val schemaIndex = WitBindingGenerationPipeline.loadSchema(
            options.toSchemaConfig(),
            messageCollector,
        )

        if (schemaIndex == null) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "Failed to load WIT schemas for module '$moduleName'. See previous errors for details.",
            )
            return
        }

        if (schemaIndex.witPackages.isEmpty() && schemaIndex.jsonSchemas.isEmpty()) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "WIT schema configuration for module '$moduleName' produced no packages or metadata.",
            )
            return
        }

        if (options.debug) {
            println(
                "WIT FIR registrar initialized with ${schemaIndex.witPackages.size} package(s) and ${schemaIndex.jsonSchemas.size} precompiled JSON schema(s)"
            )
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

private val CompilerConfiguration.moduleName: String
    get() = get(CommonConfigurationKeys.MODULE_NAME)?.takeIf { it.isNotBlank() } ?: "<unknown>"

private fun CompilerConfiguration.autoSchemaRootsViaReflection(messageCollector: MessageCollector): List<Path> {
    val raw = getStringKey("org.jetbrains.kotlin.wasm.config.WasmConfigurationKeys", "WASM_WIT_PATH")?.takeUnless { it.isBlank() } ?: return emptyList()
    return try {
        listOf(Paths.get(raw).normalize())
    } catch (ex: InvalidPathException) {
        messageCollector.report(
            CompilerMessageSeverity.ERROR,
            "Invalid WIT schema path '$raw': ${ex.message}",
        )
        emptyList()
    }
}

private fun mergePaths(primary: List<Path>, extra: List<Path>): List<Path> {
    if (extra.isEmpty()) return primary
    val merged = mutableListOf<Path>()
    val seen = LinkedHashSet<Path>()
    primary.forEach { path ->
        if (seen.add(path)) merged.add(path)
    }
    extra.forEach { path ->
        if (seen.add(path)) merged.add(path)
    }
    return merged
}

@Suppress("UNCHECKED_CAST")
private fun CompilerConfiguration.getBooleanKey(className: String, field: String): Boolean? = try {
    val key = Class.forName(className).getField(field).get(null) as org.jetbrains.kotlin.config.CompilerConfigurationKey<*>
    val value = this[key]
    value as? Boolean
} catch (_: Throwable) {
    null
}

@Suppress("UNCHECKED_CAST")
private fun CompilerConfiguration.getStringKey(className: String, field: String): String? = try {
    val key = Class.forName(className).getField(field).get(null) as org.jetbrains.kotlin.config.CompilerConfigurationKey<*>
    val value = this[key]
    value as? String
} catch (_: Throwable) {
    null
}
