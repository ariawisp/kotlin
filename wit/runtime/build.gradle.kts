description = "Runtime support library for the Kotlin WIT compiler plugin"

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.wit.gradle")
}

kotlin {
    jvm()
    // Provide a wasmWasi target so IR glue can resolve runtime types during WIT offline compilation.
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
        val wasmWasiMain by getting {
            // stdlib is provided automatically for wasm targets
            dependencies { }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
            }
        }
    }
}

// Build a local wasmWasi .klib for the runtime to be consumed by isolated compilers without publishing
val packWasmRuntimeKlib by tasks.registering(org.jetbrains.kotlin.wit.gradle.WasmKlibTask::class) {
    description = "Packs wit:runtime sources into a wasm component .klib for local consumption"
    group = "build"
    moduleName.set("kotlin-wit-runtime")
    outputDirectory.set(layout.buildDirectory.dir("klib-out"))
    // Include common sources; wasmWasiMain may be empty initially
    val commonSrc = layout.projectDirectory.dir("src/commonMain/kotlin").asFile
    if (commonSrc.isDirectory) sources.from(fileTree(commonSrc) { include("**/*.kt") })
    val wasmSrc = layout.projectDirectory.dir("src/wasmWasiMain/kotlin").asFile
    if (wasmSrc.isDirectory) sources.from(fileTree(wasmSrc) { include("**/*.kt") })

    // Provide stdlib klibs; reuse the version from the root project
    val kotlinVersion = rootProject.version.toString()
    val userHome = providers.systemProperty("user.home")
    val stdlibWasi = userHome.map { home -> file("$home/.m2/repository/org/jetbrains/kotlin/kotlin-stdlib-wasm-wasi/$kotlinVersion/kotlin-stdlib-wasm-wasi-$kotlinVersion.klib") }
    val stdlibJs = userHome.map { home -> file("$home/.m2/repository/org/jetbrains/kotlin/kotlin-stdlib-wasm-js/$kotlinVersion/kotlin-stdlib-wasm-js-$kotlinVersion.klib") }
    libraries.from(files(stdlibWasi, stdlibJs).filter { it.exists() })
}
