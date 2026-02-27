package org.jetbrains.kotlin.gradle.targets.wasm.wasmtime

import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.EnvSpec
import org.jetbrains.kotlin.gradle.utils.getFile
import java.io.File

@ExperimentalWasmDsl
abstract class WasmtimeEnvSpec : EnvSpec<WasmtimeEnv>() {
    abstract override val version: Property<String>

    private fun osArch(): Pair<String, String> {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val osPart = when {
            os.contains("mac") -> "macos"
            os.contains("win") -> "windows"
            os.contains("nux") || os.contains("linux") -> "linux"
            else -> "linux"
        }
        val archPart = when {
            arch.contains("aarch64") || arch.contains("arm64") -> "aarch64"
            arch.contains("x86_64") || arch.contains("amd64") -> "x86_64"
            else -> "x86_64"
        }
        return archPart to osPart
    }

    override val env: Provider<WasmtimeEnv> = produceEnv()

    override val executable: Provider<String> = env.map { it.executable }

    override fun produceEnv(): Provider<WasmtimeEnv> = version.map { ver ->
        val (arch, os) = osArch()
        val classifier = "$arch-$os"
        val ext = if (os == "windows") "zip" else "tar.xz"

        val rootDir = installationDirectory.getFile()
        val folderName = "wasmtime-v$ver-$classifier"
        val destDir = File(rootDir, folderName)
        val exeName = if (os == "windows") "wasmtime.exe" else "wasmtime"
        // Archives contain a top-level directory named like 'wasmtime-v<ver>-<classifier>'.
        // We extract into 'destDir', so the executable resides in 'destDir/<folderName>/<exeName>'.
        val exe = File(File(destDir, folderName), exeName).absolutePath

        WasmtimeEnv(
            download = download.get(),
            downloadBaseUrl = downloadBaseUrl.orNull,
            allowInsecureProtocol = allowInsecureProtocol.get(),
            ivyDependency = "dev.wasmtime:wasmtime:$ver:$classifier@$ext",
            executable = exe,
            dir = destDir,
        )
    }
}
