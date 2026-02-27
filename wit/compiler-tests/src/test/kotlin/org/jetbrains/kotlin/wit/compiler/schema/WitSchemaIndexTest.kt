package org.jetbrains.kotlin.wit.compiler.schema

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.wit.compiler.WitPluginOptions
import org.jetbrains.kotlin.wit.compiler.addJsonSchema
import org.jetbrains.kotlin.wit.compiler.addRoot
import org.jetbrains.kotlin.wit.compiler.setDebug
import org.jetbrains.kotlin.wit.compiler.setEnabled
import org.jetbrains.kotlin.wit.compiler.driver.WitBindingGenerationPipeline
import org.jetbrains.kotlin.wit.compiler.toSchemaConfig

class WitSchemaIndexTest {

    private val fixtureDir: Path =
        Paths.get(
            "libraries",
            "wit",
            "ast",
            "testFixtures",
            "wit-parser",
            "ui",
        ).toAbsolutePath().normalize()

    private val fixtureWit: Path = fixtureDir.resolve("resources.wit")
    private val fixtureJson: Path = fixtureDir.resolve("resources.wit.json")

    @Test
    fun `loads runtime schema from wit root`() {
        val configuration = CompilerConfiguration().apply {
            setEnabled(true)
            addRoot(fixtureWit.toString())
        }

        val options = WitPluginOptions.load(configuration)
        val messageCollector = RecordingMessageCollector()
        val schemaIndex = WitBindingGenerationPipeline.loadSchema(options.toSchemaConfig(), messageCollector)
        if (schemaIndex == null) {
            fail("Expected schema index to load data from WIT root (messages=${messageCollector.messages})")
        }

        val runtimeSchema = schemaIndex.runtimeSchema
        assertTrue(
            runtimeSchema.packages.isNotEmpty(),
            "Expected packages in runtime schema but got ${runtimeSchema.packages}",
        )

        val pkg = runtimeSchema.packages.first { it.worlds.any { world -> world.name == "w" } }
        assertTrue(pkg.interfaces.any { it.name == "foo" })
        val world = pkg.worlds.single { it.name == "w" }

        assertTrue(
            world.imports.any { it.bindingKind == BindingKind.FUNCTION && it.name == "[constructor]c" },
            "Constructor binding should be captured for world imports",
        )
        assertTrue(
            world.imports.any { it.bindingKind == BindingKind.RESOURCE },
            "Resource imports should surface as resource bindings",
        )
        val fooInterface = pkg.interfaces.single { it.name == "foo" }
        assertTrue(
            fooInterface.resources.all { it.ownHandleType != null && it.borrowHandleType != null },
            "Resource entries should include placeholder handle metadata",
        )
        assertTrue(
            runtimeSchema.sources.any { source ->
                source is WitSchemaSource.File && source.path == fixtureWit
            },
            "Original directory source path should be recorded for runtime schema",
        )
    }

    @Test
    fun `json metadata requires debug mode`() {
        val configWithoutDebug = CompilerConfiguration().apply {
            setEnabled(true)
            addJsonSchema(fixtureJson.toString())
        }
        val optionsWithoutDebug = WitPluginOptions.load(configWithoutDebug)
        val indexWithoutDebug = WitBindingGenerationPipeline.loadSchema(
            optionsWithoutDebug.toSchemaConfig(),
            RecordingMessageCollector(),
        )
        assertNull(indexWithoutDebug, "JSON inputs without debug should be ignored")

        val configWithDebug = CompilerConfiguration().apply {
            setEnabled(true)
            setDebug(true)
            addJsonSchema(fixtureJson.toString())
        }
        val optionsWithDebug = WitPluginOptions.load(configWithDebug)
        val indexWithDebug = WitBindingGenerationPipeline.loadSchema(
            optionsWithDebug.toSchemaConfig(),
            RecordingMessageCollector(),
        )
        assertNotNull(indexWithDebug, "JSON metadata should be loaded when debug mode is enabled")
        assertTrue(
            indexWithDebug.runtimeSchema.packages.isNotEmpty(),
            "Runtime schema should be populated from JSON metadata in debug mode",
        )
        assertTrue(
            indexWithDebug.runtimeSchema.sources.any { it is WitSchemaSource.Json && it.path == fixtureJson },
            "Runtime schema should record JSON source paths",
        )
    }
}

private class RecordingMessageCollector : MessageCollector {
    val messages = mutableListOf<Pair<CompilerMessageSeverity, String>>()

    override fun clear() {
        messages.clear()
    }

    override fun hasErrors(): Boolean = messages.any { it.first.isError }

    override fun report(
        severity: CompilerMessageSeverity,
        message: String,
        location: CompilerMessageSourceLocation?,
    ) {
        messages += severity to message
    }
}
