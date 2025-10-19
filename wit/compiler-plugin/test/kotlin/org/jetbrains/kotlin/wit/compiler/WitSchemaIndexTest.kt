package org.jetbrains.kotlin.wit.compiler

import java.nio.file.Paths
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.wit.compiler.driver.WitBindingGenerationPipeline
import org.jetbrains.kotlin.wit.compiler.toSchemaConfig

class WitSchemaIndexTest {
    @Test
    @Ignore("Preview 2 stdlib wiring incomplete in local fork")
    fun loadSampleRoot() {
        val configuration = CompilerConfiguration().apply {
            setEnabled(true)
            setDebug(true)
            addRoot(Paths.get("libraries/stdlib/wasm/wasi/wit-upstream/random/world.wit").toAbsolutePath().toString())
        }
        val collector = object : MessageCollector {
            override fun clear() {}
            override fun hasErrors(): Boolean = false
            override fun report(
                severity: CompilerMessageSeverity,
                message: String,
                location: CompilerMessageSourceLocation?,
            ) {
                println("[$severity] $message")
            }
        }
        val options = WitPluginOptions.load(configuration)
        val index = WitBindingGenerationPipeline.loadSchema(options.toSchemaConfig(), collector)
        assertNotNull(index, "Expected schema index to load")
    }
}
