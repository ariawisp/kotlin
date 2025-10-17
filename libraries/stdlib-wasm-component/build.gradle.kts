@file:Suppress("UNUSED_VARIABLE", "NAME_SHADOWING")

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsTargetDsl
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinTargetWithNodeJsDsl
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinWasmTargetDsl
import org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile
import org.jetbrains.kotlin.library.KOTLIN_WASM_STDLIB_NAME

plugins {
    kotlin("multiplatform")
    `maven-publish`
    signing
}

description = "Kotlin Standard Library (Wasm Component-Only)"

fun KotlinCommonCompilerOptions.mainCompilationOptions() {
    languageVersion = KotlinVersion.KOTLIN_2_3
    apiVersion = KotlinVersion.KOTLIN_2_3
    freeCompilerArgs.add("-Xstdlib-compilation")
    freeCompilerArgs.add("-Xdont-warn-on-error-suppression")
    freeCompilerArgs.add("-Xcontext-parameters")
}

val jvmBuiltinsRelativeDir = "libraries/stdlib/jvm/builtins"
val jvmBuiltinsDir = "${rootDir}/${jvmBuiltinsRelativeDir}"

fun KotlinWasmTargetDsl.commonWasmTargetConfiguration() {
    (this as KotlinTargetWithNodeJsDsl).nodejs()
    (this as KotlinJsTargetDsl).compilerOptions {
        freeCompilerArgs.addAll(
            listOf(
                "-Xallow-kotlin-package",
                "-Xexpect-actual-classes",
                "-Xklib-ir-inliner=intra-module",
                "-source-map=false",
                "-source-map-embed-sources=",
            )
        )
    }
    compilations.configureEach {
        compileTaskProvider.configure {
            compilerOptions.mainCompilationOptions()
            // Match stdlib strictness
            compilerOptions.freeCompilerArgs.add("-Xreturn-value-checker=full")
            compilerOptions.freeCompilerArgs.add("-Xir-module-name=$KOTLIN_WASM_STDLIB_NAME")
        }
    }
}

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    wasmWasi {
        commonWasmTargetConfiguration()
        binaries.library()
    }

    sourceSets {
        val commonMain by getting {
            dependencies { api(project(":kotlin-stdlib-common")) }
        }
        // Shared wasm sources (expects)
        val wasmCommonMain by creating {
            dependsOn(commonMain)
            val prepareWasmBuiltinSources by tasks.registering(Sync::class)
            kotlin {
                srcDir(prepareWasmBuiltinSources.map { it.destinationDir })
                srcDir("${rootDir}/libraries/stdlib/common-non-jvm/src")
                srcDir("${rootDir}/libraries/stdlib/native-wasm/src")
                srcDir("${rootDir}/libraries/stdlib/wasm/builtins")
                srcDir("${rootDir}/libraries/stdlib/wasm/internal")
                srcDir("${rootDir}/libraries/stdlib/wasm/runtime")
                srcDir("${rootDir}/libraries/stdlib/wasm/src")
                srcDir("${rootDir}/libraries/stdlib/wasm/stubs")
            }
            prepareWasmBuiltinSources.configure {
                val unimplementedNativeBuiltIns =
                    (file(jvmBuiltinsDir).list().toSortedSet() - file("${rootDir}/libraries/stdlib/wasm/builtins/kotlin/").list())
                        .map { "$jvmBuiltinsRelativeDir/$it" }
                val excluded = listOf("Atomics.kt", "AtomicArrays.kt", "Collections.kt", "Iterator.kt")
                unimplementedNativeBuiltIns.forEach { path ->
                    from("$rootDir/$path") {
                        into(path.dropLastWhile { it != '/' })
                        excluded.forEach { exclude(it) }
                    }
                }
                into(layout.buildDirectory.dir("src/wasm-builtin-sources"))
            }
        }
        val wasmWasiMain by getting {
            dependsOn(wasmCommonMain)
            kotlin {
                // Actuals and safe builtins (no imports)
                srcDir("${rootDir}/libraries/stdlib/wasm/wasi/builtins")
                srcDir("${projectDir}/wasm/component/src")
            }
            languageSettings { optIn("kotlin.wasm.unsafe.UnsafeWasmMemoryApi") }
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "kotlinMultiplatform") {
            artifactId = "kotlin-stdlib-wasm-component"
            pom {
                name.set("Kotlin Standard Library for WebAssembly Component Model")
                description.set("Wasm stdlib without WASI imports; includes kotlin.wasm.component and kotlin.wasm.unsafe")
                packaging = "klib"
            }
        }
    }
    repositories {
        maven {
            url = uri("${rootDir}/build/repo")
        }
    }
}
