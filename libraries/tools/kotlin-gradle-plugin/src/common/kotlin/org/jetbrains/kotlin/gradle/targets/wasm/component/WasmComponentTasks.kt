/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.wasm.component

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.utils.getExecOperations
import javax.inject.Inject

/** Optional Wasm Component Model helper tasks backed by wasm-tools. */

abstract class AssembleWasmComponentTask @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val wasmInput: RegularFileProperty

    @get:OutputFile
    abstract val componentOut: RegularFileProperty

    @get:Input
    abstract val wasmToolsExecutable: Property<String>

    @get:Input
    @get:Optional
    abstract val reallocSymbol: Property<String>

    @get:Input
    @get:Optional
    abstract val postReturnSymbol: Property<String>

    @TaskAction
    fun run() {
        val wasm = wasmInput.get().asFile
        val out = componentOut.get().asFile
        out.parentFile.mkdirs()
        val tool = wasmToolsExecutable.orNull ?: "wasm-tools"
        // Newer wasm-tools versions no longer accept --realloc/--post-return; rely on defaults.
        val cmd = mutableListOf(tool, "component", "new", "-o", out.absolutePath, wasm.absolutePath)
        execOps.exec { it.commandLine(cmd) }
    }
}

abstract class ValidateWasmComponentTask @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val componentIn: RegularFileProperty

    @get:Input
    abstract val wasmToolsExecutable: Property<String>

    @TaskAction
    fun run() {
        val tool = wasmToolsExecutable.orNull ?: "wasm-tools"
        val comp = componentIn.get().asFile
        execOps.exec { it.commandLine(tool, "validate", comp.absolutePath) }
    }
}

abstract class ValidateWitTask @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val witDir: DirectoryProperty

    @get:Input
    abstract val wasmToolsExecutable: Property<String>

    @TaskAction
    fun run() {
        val tool = wasmToolsExecutable.orNull ?: "wasm-tools"
        execOps.exec { it.commandLine(tool, "component", "wit", witDir.get().asFile.absolutePath, "-t") }
    }
}

abstract class EmbedWitIntoCoreTask @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val wasmInput: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val witDir: DirectoryProperty

    @get:OutputFile
    abstract val wasmOut: RegularFileProperty

    @get:Input
    abstract val wasmToolsExecutable: Property<String>

    @TaskAction
    fun run() {
        val tool = wasmToolsExecutable.orNull ?: "wasm-tools"
        val inFile = wasmInput.get().asFile
        val outFile = wasmOut.get().asFile
        outFile.parentFile.mkdirs()
        execOps.exec {
            it.commandLine(tool, "component", "embed", inFile.absolutePath, witDir.get().asFile.absolutePath, "-o", outFile.absolutePath)
        }
    }
}

abstract class PrintComponentWitTask @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val componentIn: RegularFileProperty

    @get:Input
    abstract val wasmToolsExecutable: Property<String>

    @TaskAction
    fun run() {
        val tool = wasmToolsExecutable.orNull ?: "wasm-tools"
        execOps.exec { it.commandLine(tool, "component", "wit", componentIn.get().asFile.absolutePath, "-t") }
    }
}

/** Convenience to register the helper tasks with sensible defaults. */
fun Project.registerWasmComponentHelperTasks() {
    val execOps = getExecOperations()

    tasks.register("assembleWasmComponent", AssembleWasmComponentTask::class.java, execOps).configure { t ->
        t.wasmToolsExecutable.set(providers.gradleProperty("wasm.tools.path").orElse("wasm-tools"))
        t.reallocSymbol.set(providers.gradleProperty("component.realloc.symbol").orElse("canonical_abi_realloc"))
        t.postReturnSymbol.set(providers.gradleProperty("component.postreturn.symbol").orElse("canonical_abi_post_return"))
    }

    tasks.register("validateWasmComponent", ValidateWasmComponentTask::class.java, execOps).configure { t ->
        t.wasmToolsExecutable.set(providers.gradleProperty("wasm.tools.path").orElse("wasm-tools"))
    }

    tasks.register("validateWit", ValidateWitTask::class.java, execOps).configure { t ->
        t.wasmToolsExecutable.set(providers.gradleProperty("wasm.tools.path").orElse("wasm-tools"))
    }

    tasks.register("embedWitIntoCore", EmbedWitIntoCoreTask::class.java, execOps).configure { t ->
        t.wasmToolsExecutable.set(providers.gradleProperty("wasm.tools.path").orElse("wasm-tools"))
    }

    tasks.register("printComponentWit", PrintComponentWitTask::class.java, execOps).configure { t ->
        t.wasmToolsExecutable.set(providers.gradleProperty("wasm.tools.path").orElse("wasm-tools"))
    }
}
