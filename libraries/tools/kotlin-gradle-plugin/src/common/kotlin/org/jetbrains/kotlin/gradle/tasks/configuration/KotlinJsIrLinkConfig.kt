/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.tasks.configuration

import org.gradle.api.InvalidUserDataException
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilationInfo
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.targets.js.KotlinWasmTargetType
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsBinaryMode
import org.jetbrains.kotlin.gradle.targets.js.ir.*
import org.jetbrains.kotlin.gradle.targets.js.npm.NpmProject
import org.gradle.api.plugins.ExtensionAware
import org.jetbrains.kotlin.gradle.targets.wasm.component.WasmComponentOptions

internal open class KotlinJsIrLinkConfig(
    private val binary: JsIrBinary,
) : BaseKotlin2JsCompileConfig<KotlinJsIrLink>(KotlinCompilationInfo(binary.compilation)) {

    private val compilation
        get() = binary.compilation

    init {
        configureTask { task ->
            // Link tasks are not affected by compiler plugin, so set to empty
            task.pluginClasspath.setFrom(objectFactory.fileCollection())

            task.dependsOn(compilation.compileTaskProvider)
            task.dependsOn(compilation.output.classesDirs)
            // We still support Gradle 6.8 in tests
            // Gradle 6.8 has a bug with verifying of not getting provider before Task execution
            task.entryModule.fileProvider(
                task.project.providers.provider {
                    val elements = compilation.output.classesDirs.elements.get()
                    elements.singleOrNull()?.asFile
                        ?: throw IllegalStateException(
                            "Only one output fo compilation expected," +
                                    "but actual: ${elements.joinToString(prefix = "[", postfix = "]") { it.toString() }}"
                        )
                }
            ).disallowChanges()

            val cacheSubdir = compilation.wasmTarget?.alias ?: "js"
            task.rootCacheDirectory.set(project.layout.buildDirectory.map { it.dir("klib/cache/$cacheSubdir/${binary.name}") })
            task.destinationDirectory.convention(
                binary.outputDirBase
                    .map { it.dir(NpmProject.DIST_FOLDER) }
            )
            task.compilerOptions.moduleName.set(compilation.outputModuleName)

            task._outputFileProperty.convention(binary.mainFile.map { it.asFile })
        }
    }

    override fun configureAdditionalFreeCompilerArguments(
        task: KotlinJsIrLink,
        compilation: KotlinCompilationInfo,
    ) {
        task.enhancedFreeCompilerArgs.value(
            task.compilerOptions.freeCompilerArgs.zip(task.modeProperty) { freeArgs, mode ->
                freeArgs.toMutableList().apply {
                    commonJsAdditionalCompilerFlags(compilation)

                    when (mode) {
                        KotlinJsBinaryMode.PRODUCTION -> {
                            configureOptions(
                                compilation,
                                ENABLE_DCE,
                                MINIMIZED_MEMBER_NAMES
                            )
                        }

                        KotlinJsBinaryMode.DEVELOPMENT -> {
                            configureOptions(
                                compilation,
                                WASM_FORCE_DEBUG_FRIENDLY
                            )
                        }
                        else -> throw InvalidUserDataException(
                            "Unknown KotlinJsBinaryMode to configure the build: $mode"
                        )
                    }

                    val alreadyDefinedOutputMode = any { it.startsWith(PER_MODULE) }
                    if (!alreadyDefinedOutputMode) {
                        task.outputGranularity.toCompilerArgument()?.let {
                            add(it)
                        }
                    }
                }
            }
        ).disallowChanges()
    }

    private fun MutableList<String>.configureOptions(
        compilation: KotlinCompilationInfo,
        vararg additionalCompilerArgs: String,
    ) {
        additionalCompilerArgs.forEach { arg ->
            if (none { it.startsWith(arg) }) add(arg)
        }

        if (compilation.platformType == KotlinPlatformType.wasm) {
            add(WASM_BACKEND)
            val wasmTargetType = (compilation.origin as KotlinJsIrCompilation).target.wasmTargetType!!
            val targetValue = if (wasmTargetType == KotlinWasmTargetType.WASI) "wasm-wasi" else "wasm-js"
            add("$WASM_TARGET=$targetValue")

            // Map Wasm Component DSL to compiler flags
            val target = (compilation.origin as KotlinJsIrCompilation).target
            val ext = (target as ExtensionAware).extensions.findByType(WasmComponentOptions::class.java)
            if (wasmTargetType == KotlinWasmTargetType.WASI) {
                add("-Xwasm-component")
                ext?.name?.orNull?.takeIf { it.isNotBlank() }?.let { add("-Xcomponent-name=$it") }
                when {
                    ext?.witFile?.isPresent == true -> ext.witFile.get().asFile.absolutePath.let { add("-Xwit=$it") }
                    ext?.witDir?.isPresent == true -> ext.witDir.get().asFile.absolutePath.let { add("-Xwit=$it") }
                }
                ext?.world?.orNull?.takeIf { it.isNotBlank() }?.let { add("-Xwit-world=$it") }
                if (ext?.importMemory?.getOrElse(false) == true) add("-Xwasm-import-memory")
            }
        }
    }
}
