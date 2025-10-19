import java.io.File
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URL
import java.net.URI
import java.net.URLClassLoader

description = "Runtime support library for the Kotlin WIT compiler plugin"

plugins {
    kotlin("multiplatform")
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
val packWasmRuntimeKlib by tasks.registering {
    group = "build"
    description = "Packs wit:runtime sources into a wasm component .klib for local consumption"
    notCompatibleWithConfigurationCache("Uses dynamic classloading and script closures; dev-only packer.")
    doLast {
        val moduleName = "kotlin-wit-runtime"
        val outDir = layout.buildDirectory.dir("klib-out").get().asFile
        outDir.mkdirs()

        // Generate minimal runtime API stubs sufficient for IR symbol resolution
        val tmpSrcDir = layout.buildDirectory.dir("klib-stubs").get().asFile
        tmpSrcDir.mkdirs()
        val stubFile = File(tmpSrcDir, "PendingBindingDelegate.kt")
        stubFile.writeText(
            """
            |package org.jetbrains.kotlin.wit.runtime
            |
            |enum class WitBindingDirection { IMPORT, EXPORT }
            |enum class WitBindingKind { FUNCTION, INTERFACE, RESOURCE }
            |
            |annotation class WitWorld(val packageId: String, val worldName: String)
            |annotation class WitBinding(
            |    val direction: WitBindingDirection,
            |    val kind: WitBindingKind,
            |    val interfaceName: String = "",
            |    val resourceName: String = "",
            |    val bindingName: String,
            |    val runtimeTarget: String = "",
            |    val isAsync: Boolean = false,
            |    val usesStreams: Boolean = false,
            |    val parameterTypeRefs: Array<String> = [],
            |    val parameterLabels: Array<String> = [],
            |    val resultTypeRefs: Array<String> = [],
            |    val resultLabels: Array<String> = [],
            |)
            |annotation class WitResource(
            |    val interfaceName: String,
            |    val resourceName: String,
            |    val ownHandleType: String = "",
            |    val borrowHandleType: String = "",
            |)
            |annotation class WitConstructor(val bindingName: String, val direction: WitBindingDirection)
            |
|interface WorldDriver {
|  val packageId: String
|  val worldName: String
|  fun bind(runtime: ComponentRuntime)
|}
|
|interface ResourceFactory
|interface BindingDelegate {
|  val packageId: String
|  val worldName: String
|  val bindingName: String
|  val direction: WitBindingDirection
|  val kind: WitBindingKind
|  val runtimeTarget: String
|  val isAsync: Boolean
|  val usesStreams: Boolean
|  fun attach(runtime: ComponentRuntime)
|}
            |
            |fun pendingBindingDelegate(
            |    packageId: String,
            |    worldName: String,
            |    bindingName: String,
            |    direction: WitBindingDirection,
            |    kind: WitBindingKind,
            |    runtimeTarget: String,
            |    isAsync: Boolean,
            |    usesStreams: Boolean,
            |    signature: BindingSignature = BindingSignature.EMPTY,
            |): BindingDelegate = throw IllegalStateException("pending binding delegate not wired")
            |
            |class ResourceType
            |class BindingSignature {
            |  companion object {
            |    val EMPTY: BindingSignature = BindingSignature()
            |  }
            |}
            |class BindingTypeRef
            |sealed class BindingValueShape {
            |  class Scalar(val name: String): BindingValueShape()
            |  class ResourceHandle(val ownership: ResourceHandleOwnership): BindingValueShape()
            |  object Unknown: BindingValueShape()
            |}
            |enum class ResourceHandleOwnership { OWN, BORROW }
            |class BindingValueMarshaller
            |
            |typealias BindingHandler = (Array<out Any?>) -> Any?
            |
            |interface ComponentRuntime {
            |  fun registerImportHandler(name: String, handler: BindingHandler): Unit = Unit
            |  fun registerExportHandler(name: String, handler: BindingHandler): Unit = Unit
            |  fun registerResource(type: ResourceType): Unit = Unit
            |  fun registerResourceFactory(
            |      type: ResourceType,
            |      constructor: (ComponentRuntime, ResourceFactory, Array<out Any?>) -> Any?
            |  ): Unit = Unit
            |  val marshaller: BindingValueMarshaller? get() = null
            |}
            |
|object GeneratedModuleRegistry {
|  private val registrars = mutableListOf<(ComponentRuntime) -> Unit>()
|  private val drivers = mutableListOf<WorldDriver>()
|  private val runtimes = mutableListOf<ComponentRuntime>()
|  fun registerModuleRegistrar(registrar: (ComponentRuntime) -> Unit) {
|    registrars += registrar
|    runtimes.forEach { registrar(it) }
|  }
|  fun registerGeneratedWorlds(vararg generated: WorldDriver) {
|    val newDrivers = generated.filter { it !in drivers }
|    drivers += newDrivers
|    if (newDrivers.isEmpty()) return
|    runtimes.forEach { runtime ->
|      newDrivers.forEach { runtime.registerDriver(it) }
|    }
|  }
|  fun registerRuntime(runtime: ComponentRuntime) {
|    if (runtime !in runtimes) {
|      runtimes += runtime
|    }
|    registrars.forEach { it(runtime) }
|    drivers.forEach { runtime.registerDriver(it) }
|  }
|}
|
|fun ComponentRuntime.installGeneratedWorlds() {
|  GeneratedModuleRegistry.registerRuntime(this)
|}
            |""".trimMargin()
        )
        val sources = listOf(stubFile)

        val args = mutableListOf(
            "-Xwasm",
            "-Xwasm-target=wasm-wasi",
            "-Xwasm-component",
            "-Xir-produce-klib-file",
            "-Xir-module-name=$moduleName",
            "-ir-output-dir",
            outDir.absolutePath,
            "-ir-output-name",
            moduleName,
        )

        // Add stdlib klibs from local Maven (same version as this build), if present
        val kotlinVersion = rootProject.version.toString()
        val home = System.getProperty("user.home", "")
        val wasiStdlib = file("$home/.m2/repository/org/jetbrains/kotlin/kotlin-stdlib-wasm-wasi/$kotlinVersion/kotlin-stdlib-wasm-wasi-$kotlinVersion.klib")
        val jsStdlib = file("$home/.m2/repository/org/jetbrains/kotlin/kotlin-stdlib-wasm-js/$kotlinVersion/kotlin-stdlib-wasm-js-$kotlinVersion.klib")
        val libs = buildList<File> {
            if (wasiStdlib.exists()) add(wasiStdlib)
            else if (jsStdlib.exists()) add(jsStdlib)
        }
        if (libs.isNotEmpty()) {
            args += listOf("-libraries", libs.joinToString(File.pathSeparator) { it.absolutePath })
        }

        sources.filter { it.isFile && it.name.endsWith(".kt") }.forEach { args += it.absolutePath }

        // Isolate the compiler like WitCodegenTask
        fun locateCompilerJar(): URL {
            // Prefer repository-provided compiler jar for correct wasm flags
            val distCompiler = rootProject.layout.projectDirectory.file("dist/kotlinc/lib/kotlin-compiler.jar").asFile
            if (distCompiler.isFile) return distCompiler.toURI().toURL()
            // Fallback to embeddable on classpath
            var found: URL? = null
            val resEnum = this::class.java.classLoader.getResources("org/jetbrains/kotlin/cli/js/K2JSCompiler.class")
            while (resEnum.hasMoreElements()) {
                val url = resEnum.nextElement()
                if (url.protocol == "jar") {
                    val spec = url.file
                    val bang = spec.indexOf('!')
                    val jarSpec = if (bang >= 0) spec.substring(0, bang) else spec
                    found = URI(jarSpec).toURL()
                    break
                }
            }
            if (found == null) {
                val cp = System.getProperty("java.class.path", "")
                for (entry in cp.split(File.pathSeparator)) {
                    if (entry.contains("kotlin-compiler-embeddable") && entry.endsWith(".jar")) {
                        found = File(entry).toURI().toURL()
                        break
                    }
                }
            }
            return found ?: throw GradleException("Unable to locate Kotlin compiler (dist or embeddable)")
        }

        val urls = mutableListOf<URL>()
        urls += locateCompilerJar()
        // Add stdlib/reflect/coroutines from local Maven for the launcher and add dist intellij/trove for envs missing shaded deps
        fun addIfExists(path: File) { if (path.isFile) urls += path.toURI().toURL() }
        addIfExists(file("$home/.m2/repository/org/jetbrains/kotlin/kotlin-stdlib/$kotlinVersion/kotlin-stdlib-$kotlinVersion.jar"))
        addIfExists(file("$home/.m2/repository/org/jetbrains/kotlin/kotlin-reflect/$kotlinVersion/kotlin-reflect-$kotlinVersion.jar"))
        // Try common coroutines artifact names
        val coroutinesBase = file("$home/.m2/repository/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm")
        if (coroutinesBase.isDirectory) {
            val versions = coroutinesBase.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }
            val latest = versions?.firstOrNull()
            if (latest != null) {
                addIfExists(File(latest, "kotlinx-coroutines-core-jvm-${latest.name}.jar"))
                addIfExists(File(latest, "kotlinx-coroutines-core-${latest.name}.jar"))
            }
        }
        // Dist jars that may be required by the compiler (trove/intellij)
        val distLib = rootProject.layout.projectDirectory.dir("dist/kotlinc/lib").asFile
        addIfExists(File(distLib, "trove4j.jar"))
        addIfExists(File(distLib, "intellij-core.jar"))

        val cl = URLClassLoader(urls.toTypedArray(), null)
        val baos = ByteArrayOutputStream()
        val ps = PrintStream(baos)
        try {
            val k2 = Class.forName("org.jetbrains.kotlin.cli.js.K2JSCompiler", true, cl)
            val compiler = k2.getDeclaredConstructor().newInstance()
            val exec = k2.getMethod("execFullPathsInMessages", PrintStream::class.java, Array<String>::class.java)
            val exitCode = exec.invoke(compiler, ps, args.toTypedArray())
            val name = exitCode.javaClass.getMethod("name").invoke(exitCode) as String
            if (name != "OK") throw GradleException("Wasm klib compilation failed (exit=$name)\n${baos.toString(Charsets.UTF_8)}")
        } finally {
            ps.close(); try { cl.close() } catch (_: Exception) {}
        }
    }
}
