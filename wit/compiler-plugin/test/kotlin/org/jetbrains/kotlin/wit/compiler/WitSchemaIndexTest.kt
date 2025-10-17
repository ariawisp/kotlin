package org.jetbrains.kotlin.wit.compiler

import java.nio.file.Paths
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.wit.compiler.schema.WitSchemaIndex

class WitSchemaIndexTest {
    @Test
    @Ignore("Preview 2 stdlib wiring incomplete in local fork")
    fun loadSampleRoot() {
        val configuration = CompilerConfiguration().apply {
            setEnabled(true)
            setDebug(true)
            addRoot(Paths.get("wit/e2e-harness-jvm/src/test/wit/root.wit").toAbsolutePath().toString())
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
        val index = WitSchemaIndex.load(options, collector)
        assertNotNull(index, "Expected schema index to load")
    }
}
