import org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerOptions
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget

description = "Runtime support library for the Kotlin WIT compiler plugin"

plugins {
    kotlin("multiplatform")
}

fun KotlinCommonCompilerOptions.mainCompilationOptions() {
    freeCompilerArgs.add("-Xdont-warn-on-error-suppression")
    freeCompilerArgs.add("-Xcontext-parameters")
}

kotlin {
    jvm()

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmWasi {
        binaries.library()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(kotlin("stdlib"))
            }
        }
        val componentMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(kotlin("stdlib"))
            }
        }
        val wasmWasiMain by getting {
            dependsOn(componentMain)
            dependencies {
                implementation(kotlin("stdlib"))
            }
        }
        val wasmWasiTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
            }
        }
    }
}

afterEvaluate {
    val wasmWasiTarget = kotlin.targets
        .withType<KotlinJsIrTarget>()
        .firstOrNull { it.platformType == KotlinPlatformType.wasm && it.wasmTargetType?.name == "WASI" }
        ?: return@afterEvaluate

    val componentMain = kotlin.sourceSets.getByName("componentMain")
    val mainCompilation = wasmWasiTarget.compilations.getByName("main")

    val componentCompilation = wasmWasiTarget.compilations.findByName("component")
        ?: wasmWasiTarget.compilations.create("component").apply {
            defaultSourceSet.dependsOn(componentMain)
            associateWith(mainCompilation)
        }

    componentCompilation.compileTaskProvider.configure {
        compilerOptions.mainCompilationOptions()
        compilerOptions.freeCompilerArgs.add("-Xir-module-name=kotlin-wit-runtime")
    }

    wasmWasiTarget.binaries.library(componentCompilation)
}

val syncWasmRuntimeKlib by tasks.registering(Sync::class) {
    group = "build"
    description = "Collects the wasm runtime klib for consumption by WIT tooling"
    dependsOn("compileProductionLibraryKotlinWasmWasi")
    val artifact = layout.buildDirectory.file("libs/runtime-wasm-wasi-${project.version}.klib")
    from(artifact)
    into(layout.buildDirectory.dir("klib"))
    rename { "kotlin-wit-runtime.klib" }
}

tasks.named("assemble") {
    dependsOn(syncWasmRuntimeKlib)
}
