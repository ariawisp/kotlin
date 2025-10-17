package org.jetbrains.kotlin.wit.compiler.tests

import java.nio.file.Path
import java.nio.file.Paths
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.runners.ir.AbstractFirPsiJvmIrTextTest

abstract class AbstractWitIrTest : AbstractFirPsiJvmIrTextTest() {
    override fun configure(builder: TestConfigurationBuilder) {
        super.configure(builder)
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
