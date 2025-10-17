package org.jetbrains.kotlin.gradle.targets.wasm.wasmtime

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinWasmTargetDsl
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmPlatformDisambiguator
import org.jetbrains.kotlin.gradle.tasks.registerTask
import org.gradle.api.GradleException

/**
 * Select Wasmtime as the runtime environment for the current Wasm/WASI target.
 * Applies the Wasmtime plugin, registers the setup task, and exposes the
 * executable via the env spec. Also marks the environment as selected for
 * the diagnostics checker.
 */
@ExperimentalWasmDsl
fun KotlinWasmTargetDsl.wasmtime() {
    val project = this.project
    if (!WasmtimePlugin.isInternalRunnerEnabled(project)) {
        // External usage: only mark the environment as selected to silence diagnostics.
        project.extensions.extraProperties.set("kotlin.wasm.wasmtime.enabled", true)
        // Do NOT install, download, or register run tasks for external builds.
        return
    }
    // Internal usage: apply plugin and fully configure the environment
    val spec = WasmtimePlugin.applyWithEnvSpec(project)
    // Provide a default version if none is specified
    if (!spec.version.isPresent) spec.version.convention("37.0.1")
    if (!spec.downloadBaseUrl.isPresent) spec.downloadBaseUrl.convention("https://github.com/bytecodealliance/wasmtime/releases/download/")
    if (!spec.download.isPresent) spec.download.convention(true)
    if (!spec.allowInsecureProtocol.isPresent) spec.allowInsecureProtocol.convention(false)
    if (!spec.installationDirectory.isPresent) spec.installationDirectory.convention(project.layout.buildDirectory.dir("tools/wasmtime"))

    // Register setup task for this target
    val setupName = WasmPlatformDisambiguator.extensionName(WasmtimeSetupTask.BASE_NAME)
    project.tasks.named(setupName, WasmtimeSetupTask::class.java)
    this.binaries // touch to ensure target is configured

    // Mark wasmtime enabled to silence advisory
    project.extensions.extraProperties.set("kotlin.wasm.wasmtime.enabled", true)
    // Expose executable provider to build scripts (for Exec tasks without plugin classes)
    project.extensions.extraProperties.set("wasmtimeExecutableProvider", spec.executable)

    // Register simple run tasks (development / production) using Exec
    val devPath = "build/compileSync/wasmWasi/main/developmentExecutable/kotlin/${project.name}.wasm"
    val prodPath = "build/compileSync/wasmWasi/main/productionExecutable/kotlin/${project.name}.wasm"
    project.registerTask<org.gradle.api.tasks.Exec>(
        WasmPlatformDisambiguator.extensionName("WasmtimeDevelopmentRun")
    ) {
        it.group = "wasmtime"
        it.description = "Run development executable via Wasmtime"
        it.dependsOn(setupName, "compileDevelopmentExecutableKotlinWasmWasi")
        it.doFirst { _ ->
            val exe = spec.executable.get()
            it.workingDir = project.file(devPath).parentFile
            it.commandLine(exe, devPath)
        }
    }
    project.registerTask<org.gradle.api.tasks.Exec>(
        WasmPlatformDisambiguator.extensionName("WasmtimeProductionRun")
    ) {
        it.group = "wasmtime"
        it.description = "Run production executable via Wasmtime"
        it.dependsOn(setupName, "compileProductionExecutableKotlinWasmWasi")
        it.doFirst { _ ->
            val exe = spec.executable.get()
            it.workingDir = project.file(prodPath).parentFile
            it.commandLine(exe, prodPath)
        }
    }
}
