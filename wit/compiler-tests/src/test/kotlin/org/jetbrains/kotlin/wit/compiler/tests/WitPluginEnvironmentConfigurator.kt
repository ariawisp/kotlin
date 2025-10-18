package org.jetbrains.kotlin.wit.compiler.tests

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.config.IrVerificationMode
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.wit.compiler.WitPluginOptions
import org.jetbrains.kotlin.wit.compiler.addJsonSchema
import org.jetbrains.kotlin.wit.compiler.driver.WitBindingGenerationPipeline
import org.jetbrains.kotlin.wit.compiler.setDebug
import org.jetbrains.kotlin.wit.compiler.setEnabled
import org.jetbrains.kotlin.wit.compiler.fir.WitFirExtensionRegistrar
import org.jetbrains.kotlin.wit.compiler.ir.WitIrGenerationExtension
import org.jetbrains.kotlin.wit.compiler.toSchemaConfig

@OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)
class WitPluginEnvironmentConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
        configuration.setEnabled(true)
        configuration.setDebug(true)
        configuration.put(CommonConfigurationKeys.VERIFY_IR, IrVerificationMode.NONE)

        val moduleBase = module.files.firstOrNull()?.originalFile?.parentFile?.absoluteFile
            ?: error("Unable to locate module base directory for ${module.name}")

        val schemaResources = module.directives[WitPluginDirectives.WIT_SCHEMA]
            .ifEmpty { listOf(DEFAULT_SCHEMA_RESOURCE) }

        val workspaceRoot = java.nio.file.Paths.get("").toAbsolutePath().toFile()
        val moduleRoot = moduleBase.parentFile?.parentFile

        schemaResources.forEach { resourceName ->
            val sourceFileCandidates = listOf(
                java.io.File(resourceName),
                moduleRoot?.let { java.io.File(it, resourceName) },
                java.io.File(workspaceRoot, resourceName),
                java.io.File(moduleBase, resourceName),
                java.io.File(moduleBase, "schemas/$resourceName"),
            )
            val sourceFile = sourceFileCandidates.firstOrNull { candidate ->
                candidate != null && candidate.exists()
            }
                ?: run {
                    val resourceCandidates = if (resourceName.contains('/')) {
                        listOf(resourceName, RESOURCE_PREFIX + resourceName.substringAfterLast('/'))
                    } else {
                        listOf(RESOURCE_PREFIX + resourceName)
                    }
                    val resourceUrl = resourceCandidates.firstNotNullOfOrNull { candidatePath ->
                        javaClass.classLoader.getResource(candidatePath)
                    }
                    resourceUrl?.let { url ->
                        val temp = Files.createTempFile("wit-schema-resource", ".json")
                        url.openStream().use { input -> Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING) }
                        temp.toFile()
                    }
                }?.takeIf { it.exists() }
                ?: error("Unable to locate WIT schema file '$resourceName' near ${moduleBase.absolutePath}")

            val tempFile: Path = Files.createTempFile("wit-schema", ".json")
            Files.copy(sourceFile.toPath(), tempFile, StandardCopyOption.REPLACE_EXISTING)
            configuration.addJsonSchema(tempFile.toAbsolutePath().toString())
        }
    }

    override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
        module: TestModule,
        configuration: CompilerConfiguration,
    ) {
        val options = WitPluginOptions.load(configuration)
        if (!options.enabled) return
        val messageCollector = configuration.messageCollector
        val schemaIndex = WitBindingGenerationPipeline.loadSchema(
            options.toSchemaConfig(),
            messageCollector,
        ) ?: return

        org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter.registerExtension(
            WitFirExtensionRegistrar(options, schemaIndex),
        )
        org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension.registerExtension(
            WitIrGenerationExtension(options.debug, schemaIndex),
        )
    }

    private val CompilerConfiguration.messageCollector: MessageCollector
        get() = get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)

    private companion object {
        private const val RESOURCE_PREFIX = "org/jetbrains/kotlin/wit/compiler/tests/schemas/"
        private const val DEFAULT_SCHEMA_RESOURCE = "world_with_resources.json"
    }
}
