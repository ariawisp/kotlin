package org.jetbrains.kotlin.wit.compiler.tests

import java.nio.file.Path
import java.nio.file.Paths
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.configuration.baseFirDiagnosticTestConfiguration
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives
import org.jetbrains.kotlin.test.runners.AbstractKotlinCompilerTest

abstract class AbstractWitFirDiagnosticTest : AbstractKotlinCompilerTest() {
    override fun configure(builder: TestConfigurationBuilder) {
        builder.baseFirDiagnosticTestConfiguration()
        builder.defaultDirectives {
            +FirDiagnosticsDirectives.ENABLE_PLUGIN_PHASES
            +FirDiagnosticsDirectives.FIR_DUMP
            FirDiagnosticsDirectives.FIR_PARSER with FirParser.Psi
        }
        builder.useDirectives(WitPluginDirectives)
        builder.useConfigurators(::WitPluginEnvironmentConfigurator)
    }

    override fun runTest(filePath: String) {
        val inputPath = Paths.get(filePath)
        val resolved = if (inputPath.isAbsolute) {
            inputPath
        } else {
            BASE_TEST_DATA.resolve(filePath)
        }.normalize()
        super.runTest(resolved.toString())
    }

    private companion object {
        private val BASE_TEST_DATA: Path = Paths.get("wit", "compiler-tests")
    }
}
