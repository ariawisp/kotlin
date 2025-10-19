/*
 * Verifies that the WASI stdlib resolves to the preview2/component build by default.
 */
package org.jetbrains.kotlin.gradle

import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.testbase.*
import org.junit.jupiter.api.DisplayName
@MppGradlePluginTests
class WasmWasiStdlibIT : KGPBaseTest() {

    override val defaultBuildOptions: BuildOptions
        get() = super.defaultBuildOptions.copy(isolatedProjects = BuildOptions.IsolatedProjectsMode.DISABLED)

    @DisplayName("WASI stdlib resolves without opt-in")
    @GradleTest
    fun resolvesWasiStdlib(gradleVersion: GradleVersion) {
        project("wasm-wasi-library", gradleVersion) {
            build("dependencies", "--configuration", "wasmWasiRuntimeClasspath") {
                assertOutputContains("org.jetbrains.kotlin:kotlin-stdlib-wasm-wasi")
                assertOutputDoesNotContain("org.jetbrains.kotlin:kotlin-stdlib-wasm-component")
            }
        }
    }

}
