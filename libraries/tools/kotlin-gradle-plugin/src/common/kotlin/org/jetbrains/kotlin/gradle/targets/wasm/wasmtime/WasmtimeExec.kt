/*
 * Simple Wasmtime run task integrated with the Wasmtime env spec.
 */
package org.jetbrains.kotlin.gradle.targets.wasm.wasmtime

import org.gradle.api.tasks.AbstractExecTask
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrCompilation
import org.jetbrains.kotlin.gradle.tasks.registerTask
import org.gradle.api.GradleException

@ExperimentalWasmDsl
@DisableCachingByDefault
abstract class WasmtimeExec internal constructor() : AbstractExecTask<WasmtimeExec>(WasmtimeExec::class.java) {
    companion object {
        fun register(
            compilation: KotlinJsIrCompilation,
            name: String,
            configuration: WasmtimeExec.() -> Unit = {},
        ): TaskProvider<WasmtimeExec> {
            val target = compilation.target
            val project = target.project
            if (!WasmtimePlugin.isInternalRunnerEnabled(project)) {
                throw GradleException(
                    "WasmtimeExec is internal-only. Provide your own Exec task wired to your wasmtime binary."
                )
            }
            val wasmtime = WasmtimePlugin.applyWithEnvSpec(project)
            return project.registerTask(
                name
            ) {
                it.executable = wasmtime.executable.get()
                // Ensure Wasmtime is installed and the wasm is compiled
                it.dependsOn(project.tasks.named(org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmPlatformDisambiguator.extensionName(WasmtimeSetupTask.BASE_NAME)))
                it.dependsOn(compilation.compileTaskProvider)
                it.configuration()
            }
        }
    }
}
