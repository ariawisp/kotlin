package org.jetbrains.kotlin.wit.compiler.driver

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector

class WitBindingGenerationPipelineTest {
    @Test
    fun `load schema from wit root`() {
        val tempDir = createTempDirectory("wit-schema")
        tempDir.resolve("sample.wit").writeText(
            """
            package example:math;

            interface math {
                add: func(a: s32, b: s32) -> s32;
            }

            world calculator {
                export math;
            }
            """.trimIndent(),
        )

        val config = WitSchemaConfig(
            rootPaths = listOf(tempDir),
        )
        val collector = RecordingMessageCollector()

        val schemaIndex = WitBindingGenerationPipeline.loadSchema(config, collector)
            ?: fail("Expected schema index to load from temporary WIT root: ${collector.messages}")

        assertTrue(schemaIndex.runtimeSchema.packages.isNotEmpty(), "Runtime schema should contain packages")
        assertTrue(
            schemaIndex.runtimeSchema.worlds().any { it.name == "calculator" },
            "Temporary schema should expose the calculator world",
        )
    }

    @Test
    fun `json metadata requires explicit opt in`() {
        val tempJson = Files.createTempFile("wit-schema", ".json").also { it.writeText("{}") }
        val collector = RecordingMessageCollector()

        val withoutFlag = WitSchemaConfig(
            jsonSchemas = listOf(tempJson),
        )

        val indexWithoutFlag = WitBindingGenerationPipeline.loadSchema(withoutFlag, collector)
        assertNull(indexWithoutFlag, "JSON schemas should be skipped when allowJsonSchemas=false")
        assertTrue(
            collector.messages.any { (severity, message) ->
                severity == CompilerMessageSeverity.WARNING && message.contains("Ignoring")
            },
            "Expected warning when JSON schemas are provided without debug opt-in",
        )
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

    private fun org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeSchema.worlds(): List<org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeWorld> =
        packages.flatMap { it.worlds }
}
