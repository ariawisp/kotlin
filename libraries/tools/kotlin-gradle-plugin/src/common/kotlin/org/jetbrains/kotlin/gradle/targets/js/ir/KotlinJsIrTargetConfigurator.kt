/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:OptIn(ExperimentalWasmDsl::class)

package org.jetbrains.kotlin.gradle.targets.js.ir

import org.gradle.language.base.plugins.LifecycleBasePlugin.ASSEMBLE_TASK_NAME
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JsSourceMapEmbedMode
import org.jetbrains.kotlin.gradle.dsl.KotlinJsCompilerOptions
import org.jetbrains.kotlin.gradle.plugin.KotlinOnlyTargetConfigurator
import org.jetbrains.kotlin.gradle.plugin.mpp.isMain
import org.jetbrains.kotlin.gradle.targets.js.KotlinWasmTargetType
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsBinaryMode
import org.jetbrains.kotlin.gradle.tasks.dependsOn
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Provider
import org.gradle.api.file.RegularFile
import org.jetbrains.kotlin.gradle.targets.wasm.component.WasmComponentOptions
import org.jetbrains.kotlin.gradle.targets.wasm.component.AssembleWasmComponentTask
import org.jetbrains.kotlin.gradle.targets.wasm.component.ValidateWasmComponentTask
import org.jetbrains.kotlin.gradle.targets.wasm.component.PrintComponentWitTask
import org.jetbrains.kotlin.gradle.utils.getExecOperations

open class KotlinJsIrTargetConfigurator :
    KotlinOnlyTargetConfigurator<KotlinJsIrCompilation, KotlinJsIrTarget>(true) {

    override fun configureTarget(target: KotlinJsIrTarget) {
        super.configureTarget(target)

        val assemble = target.project.tasks.named(ASSEMBLE_TASK_NAME)

        target.compilations.all { compilation ->
            if (compilation.isMain()) {
                val wasmComponentExt = if (target.wasmTargetType == KotlinWasmTargetType.WASI) {
                    (target as ExtensionAware).extensions.findByType(WasmComponentOptions::class.java)
                } else null

                if (wasmComponentExt != null) {
                    target.project.afterEvaluate {
                        if (wasmComponentExt.witDir.isPresent && wasmComponentExt.witFile.isPresent) {
                            throw org.gradle.api.GradleException(
                                "Both 'witDir' and 'witFile' are set in wasmWasi.component; please specify only one"
                            )
                        }
                    }
                }

                compilation.binaries
                    .matching { it.mode == KotlinJsBinaryMode.PRODUCTION }
                    .all { binary ->
                        val wasmBinary = binary as? WasmBinary
                        if (target.wasmTargetType != null && wasmBinary != null) {
                            assemble.dependsOn(wasmBinary.optimizeTask)
                        } else {
                            assemble.dependsOn(binary.linkTask)
                        }

                        if (target.wasmTargetType == KotlinWasmTargetType.WASI && wasmBinary != null) {
                            val ext = wasmComponentExt
                            val linkTask = wasmBinary.linkTask
                            val compiledWasmFile: Provider<RegularFile> = linkTask.flatMap { link ->
                                link.destinationDirectory.locationOnly.zip(link.compilerOptions.moduleName) { destDir, moduleName ->
                                    destDir.file("$moduleName.wasm")
                                }
                            }

                            val componentOut: Provider<RegularFile> = linkTask.flatMap { link ->
                                link.destinationDirectory.locationOnly.zip(link.compilerOptions.moduleName) { destDir, moduleName ->
                                    destDir.file("$moduleName.component.wasm")
                                }
                            }

                            fun cap(s: String) = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                            val assembleName = "${wasmBinary.compilation.target.targetName}${cap(wasmBinary.name)}AssembleWasmComponent"
                            val validateName = "${wasmBinary.compilation.target.targetName}${cap(wasmBinary.name)}ValidateWasmComponent"
                            val printWitName = "${wasmBinary.compilation.target.targetName}${cap(wasmBinary.name)}PrintComponentWit"

                            val execOps = target.project.getExecOperations()
                            val assembleComp = target.project.tasks.register(assembleName, AssembleWasmComponentTask::class.java, execOps)
                            assembleComp.configure { t ->
                                t.dependsOn(linkTask)
                                t.wasmInput.set(compiledWasmFile)
                                t.componentOut.set(componentOut)
                                t.wasmToolsExecutable.convention(target.project.providers.gradleProperty("wasm.tools.path").orElse("wasm-tools"))
                                t.reallocSymbol.convention("canonical_abi_realloc")
                                t.postReturnSymbol.convention("canonical_abi_post_return")
                                t.adapters.convention(emptyList())
                                ext?.let { options ->
                                    t.adapters.set(options.adapters)
                                }
                            }

                            val validateComp = target.project.tasks.register(validateName, ValidateWasmComponentTask::class.java, execOps)
                            validateComp.configure { t ->
                                t.dependsOn(assembleComp)
                                t.componentIn.set(componentOut)
                                t.wasmToolsExecutable.convention(target.project.providers.gradleProperty("wasm.tools.path").orElse("wasm-tools"))
                            }

                            val printCompWit = target.project.tasks.register(printWitName, PrintComponentWitTask::class.java, execOps)
                            printCompWit.configure { t ->
                                t.dependsOn(assembleComp)
                                t.componentIn.set(componentOut)
                                t.wasmToolsExecutable.convention(target.project.providers.gradleProperty("wasm.tools.path").orElse("wasm-tools"))
                            }

                            assemble.dependsOn(assembleComp)
                        }
                    }
            }
        }
    }

    internal companion object {
        internal fun KotlinJsCompilerOptions.configureJsDefaultOptions() {
            sourceMap.convention(true)
            sourceMapEmbedSources.convention(JsSourceMapEmbedMode.SOURCE_MAP_SOURCE_CONTENT_NEVER)
        }
    }
}
