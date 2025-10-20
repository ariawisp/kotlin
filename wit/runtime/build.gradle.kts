import org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerOptions

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
        compilations.getByName("main").compileTaskProvider.configure {
            compilerOptions.mainCompilationOptions()
            compilerOptions.freeCompilerArgs.add("-Xir-module-name=kotlin-wit-runtime")
        }
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
        val wasmWasiMain by getting {
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

// Gate runtime tasks so we can skip them during bootstrap seeding
val witSkipBuild = providers.gradleProperty("wit.skipBuild").map { it.toBoolean() }.orElse(false)
tasks.matching { it.name.startsWith("compileKotlin") || it.name == "jar" }.configureEach {
    onlyIf { !witSkipBuild.get() }
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
