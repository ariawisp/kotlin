package org.jetbrains.kotlin.gradle.targets.wasm.wasmtime

import org.gradle.api.Project
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmPlatformDisambiguator
import org.jetbrains.kotlin.gradle.targets.web.HasPlatformDisambiguator
import org.jetbrains.kotlin.gradle.tasks.CleanDataTask
import org.jetbrains.kotlin.gradle.tasks.internal.CleanableStore
import org.jetbrains.kotlin.gradle.tasks.registerTask

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
internal abstract class WasmtimePlugin : org.gradle.api.Plugin<Project> {
    override fun apply(target: Project) {
        if (!isInternalRunnerEnabled(target)) {
            throw GradleException(
                "Wasmtime runner is internal-only. Remove wasmtime() and provide your own runtime (e.g., Exec). " +
                    "To enable internally, set -P$INTERNAL_GATE_PROPERTY=true in the Kotlin repo."
            )
        }
        target.plugins.apply(BasePlugin::class.java)

        // Create env spec extension
        val spec = target.extensions.create(
            EXTENSION_NAME,
            WasmtimeEnvSpec::class.java
        )

        // Register setup task
        val setupTaskName = WasmPlatformDisambiguator.extensionName(WasmtimeSetupTask.BASE_NAME)
        target.registerTask<WasmtimeSetupTask>(
            setupTaskName,
            listOf(spec)
        ) { t ->
            t.group = TASKS_GROUP_NAME
            t.description = "Download and install Wasmtime"
            t.configuration = t.ivyDependencyProvider.map { ivy ->
                target.configurations.detachedConfiguration(target.dependencies.create(ivy)).also { it.isTransitive = false }
            }
        }
        // Expose setup task name for buildscripts which cannot see plugin classes
        if (!target.extensions.extraProperties.has("wasmtimeSetupTaskName")) {
            target.extensions.extraProperties["wasmtimeSetupTaskName"] = setupTaskName
        }

        // Optional clean task
        target.registerTask<CleanDataTask>(
            WasmPlatformDisambiguator.extensionName(
                "wasmtime" + CleanDataTask.NAME_SUFFIX,
                prefix = null,
            )
        ) { t ->
            t.cleanableStoreProvider = spec.installationDirectory.map { CleanableStore[it.asFile.path] }
            t.group = TASKS_GROUP_NAME
            t.description = "Clean Wasmtime installation"
        }
    }

    companion object : HasPlatformDisambiguator by WasmPlatformDisambiguator {
        private const val EXTENSION_NAME = "WasmtimeSpec"
        private const val TASKS_GROUP_NAME = "wasmtime"
        internal const val INTERNAL_GATE_PROPERTY = "kotlin.internal.enableWasmtimeRunner"

        internal fun isInternalRunnerEnabled(project: Project): Boolean {
            val raw = project.findProperty(INTERNAL_GATE_PROPERTY)?.toString()
                ?: System.getProperty(INTERNAL_GATE_PROPERTY)
            return when (raw?.lowercase()) {
                "true", "yes", "1" -> true
                else -> false
            }
        }

        fun applyWithEnvSpec(project: Project): WasmtimeEnvSpec {
            if (!isInternalRunnerEnabled(project)) {
                throw GradleException(
                    "Wasmtime runner is internal-only. Remove wasmtime() and provide your own runtime (e.g., Exec). " +
                        "To enable internally, set -P$INTERNAL_GATE_PROPERTY=true in the Kotlin repo."
                )
            }
            project.plugins.apply(WasmtimePlugin::class.java)
            return project.extensions.getByName(EXTENSION_NAME) as WasmtimeEnvSpec
        }
    }
}
