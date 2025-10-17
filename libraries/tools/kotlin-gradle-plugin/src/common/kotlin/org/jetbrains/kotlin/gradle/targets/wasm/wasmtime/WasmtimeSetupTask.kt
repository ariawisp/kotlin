@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
package org.jetbrains.kotlin.gradle.targets.wasm.wasmtime

import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Internal
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.targets.js.AbstractSetupTask
import java.io.File
import javax.inject.Inject

@DisableCachingByDefault
abstract class WasmtimeSetupTask @Inject constructor(
    settings: WasmtimeEnvSpec,
) : AbstractSetupTask<WasmtimeEnv, WasmtimeEnvSpec>(settings) {

    @get:Internal
    override val artifactPattern: String
        get() = "v[revision]/[artifact]-v[revision]-[classifier].[ext]"

    @get:Internal
    override val artifactModule: String
        get() = "dev.wasmtime"

    @get:Internal
    override val artifactName: String
        get() = "wasmtime"

    @get:Inject
    abstract val execOps: ExecOperations

    // Archive and destination are already exposed via providers in AbstractSetupTask

    override fun extract(archive: File) {
        val dest = destinationProvider.get().asFile
        dest.mkdirs()
        if (archive.extension == "zip") {
            fs.copy { spec ->
                spec.from(archiveOperations.zipTree(archive))
                spec.into(dest)
            }
        } else {
            // Expect tar.xz; use system tar with -J support
            execOps.exec {
                it.commandLine("tar", "-xJf", archive.absolutePath, "-C", dest.absolutePath)
            }
        }
        // Make binary executable on Unix
        val exe = env.get().executable
        File(exe).setExecutable(true)
    }

    companion object {
        const val BASE_NAME: String = "WasmtimeSetup"
    }
}
