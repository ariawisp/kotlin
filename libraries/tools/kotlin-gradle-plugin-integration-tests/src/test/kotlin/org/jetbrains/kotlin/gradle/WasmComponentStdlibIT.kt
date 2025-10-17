/*
 * Verifies that kotlin.wasm.componentOnly=true resolves the component-only stdlib via attributes
 * without relying on substitution.
 */
package org.jetbrains.kotlin.gradle

import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.testbase.*
import org.junit.jupiter.api.DisplayName
import kotlin.io.path.appendText

@MppGradlePluginTests
class WasmComponentStdlibIT : KGPBaseTest() {

    override val defaultBuildOptions: BuildOptions
        get() = super.defaultBuildOptions.copy(isolatedProjects = BuildOptions.IsolatedProjectsMode.DISABLED)

    @DisplayName("Wasm component stdlib resolves by attributes without opt-in")
    @GradleTest
    fun resolvesComponentStdlib(gradleVersion: GradleVersion) {
        project("wasm-wasi-library", gradleVersion) {
            // Ask Gradle for the wasmWasi runtime classpath
            build("dependencies", "--configuration", "wasmWasiComponentRuntimeClasspath") {
                assertOutputContains("org.jetbrains.kotlin:kotlin-stdlib-wasm-component")
                // Guard against accidental WASI stdlib resolution
                assertOutputDoesNotContain("org.jetbrains.kotlin:kotlin-stdlib-wasm-wasi")
            }
        }
    }

    @DisplayName("Component-only flag cannot be disabled")
    @GradleTest
    fun cannotDisableComponentMode(gradleVersion: GradleVersion) {
        project("wasm-wasi-library", gradleVersion) {
            gradleProperties.appendText("\nkotlin.wasm.componentOnly=false\n")

            buildAndFail("dependencies", "--configuration", "wasmWasiComponentRuntimeClasspath") {
                assertOutputContains("kotlin.wasm.componentOnly=true")
            }
        }
    }
}
