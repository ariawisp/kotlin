import org.gradle.api.DefaultTask
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.targets.wasm.component.component
import org.jetbrains.kotlin.gradle.targets.wasm.wasmtime.wasmtime
import org.jetbrains.kotlin.gradle.targets.wasm.wasmtime.WasmtimeEnvSpec
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

plugins {
    kotlin("multiplatform")
}

description = "Preview-2 Wasm Component sample (Wasmtime harness)"

val wasiPreview2WitDir = layout.projectDirectory.dir("../../libraries/stdlib/wasm/wasi/wit-upstream")
val wasiPreview2World = "wasi:cli/command"

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmWasi {
        // Enable internal Wasmtime environment + simple run tasks
        wasmtime()
        // Bump Wasmtime if the extension is present
        val ext = project.extensions.findByName("WasmtimeSpec") as? WasmtimeEnvSpec
        if (ext != null && !ext.version.isPresent) {
            ext.version.convention("39.0.0")
        }
        binaries.executable()

        // Configure the component model helper DSL (used in Stage 2)
        component {
            name.convention(project.name)
            // Point at the synced upstream WASI WIT bundle in this repo
            witDir.set(wasiPreview2WitDir)
            world.set(wasiPreview2World)
            importMemory.convention(false)
        }
    }

    sourceSets {
        val wasmWasiMain by getting {
            // Include generated shim source so the core module exports `run`
            kotlin.srcDir(layout.buildDirectory.dir("generated/wasiCliShim/kotlin"))
            dependencies {
                // Prefer local stdlib project so we pick up Preview-2 changes
                implementation(project(":kotlin-stdlib"))
            }
        }
    }
}

private val wasmImportsAttribute: Attribute<String> =
    Attribute.of("org.jetbrains.kotlin.wasm.imports", String::class.java)

configurations.named("wasmWasiRuntimeClasspath") {
    attributes.attribute(wasmImportsAttribute, "preview2")
}

configurations.named("wasmWasiCompileClasspath") {
    attributes.attribute(wasmImportsAttribute, "preview2")
}

// Alias lifecycle tasks to the per-binary assemble/validate created by the plugin
// Provide our own component assembly using newer wasm-tools CLI (no --realloc/--post-return)
// Disable the default helper task if present (older CLI flags)
tasks.matching { it.name == "wasmWasiProductionExecutableAssembleWasmComponent" }.configureEach { enabled = false }

// Ensure recompile picks up source changes during local iteration
tasks.matching { it.name == "compileProductionExecutableKotlinWasmWasi" }.configureEach {
    outputs.upToDateWhen { false }
}

// Generate a tiny shim that maps Kotlin main() to the wasi:cli/command export `run`.
// Note: wasm-tools component embed expects canonical Preview-2 export names
// like `cm32p2|wasi:cli/run@0.2|run` plus a matching `run_post` stub.
val generateWasiCliRunShim = tasks.register("generateWasiCliRunShim") {
    val outDir = layout.buildDirectory.dir("generated/wasiCliShim/kotlin").get().asFile
    outputs.dir(outDir)
    doLast {
        outDir.mkdirs()
        val file = outDir.resolve("RunShim.kt")
        file.writeText(
            """
            |@file:Suppress("RedundantVisibilityModifier")
            |import kotlin.wasm.ExperimentalWasmInterop
            |
            |@OptIn(ExperimentalWasmInterop::class)
            |@kotlin.wasm.WasmExport(name = "cm32p2|wasi:cli/run@0.2|run")
            |public fun run(): Int {
            |    return try {
            |        main()
            |        0
            |    } catch (_: Throwable) {
            |        1
            |    }
            |}
            |
            |// Post-return stub required by canonical ABI naming for Preview-2
            |@OptIn(ExperimentalWasmInterop::class)
            |@kotlin.wasm.WasmExport(name = "cm32p2|wasi:cli/run@0.2|run_post")
            |public fun run_post(@Suppress("UNUSED_PARAMETER") rc: Int) {
            |    // No resources to clean up for `result` with no payload; rc is ignored.
            |}
            |""".trimMargin()
        )
    }
}

// Ensure the shim exists before compiling the wasmWasi target
tasks.matching { it.name == "compileKotlinWasmWasi" || it.name == "compileProductionExecutableKotlinWasmWasi" }
    .configureEach { dependsOn(generateWasiCliRunShim) }

abstract class AssemblePreview2Component @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val world: Property<String>

    @get:InputDirectory
    abstract val witDir: DirectoryProperty

    @get:InputDirectory
    abstract val prodDir: DirectoryProperty

    @get:org.gradle.api.tasks.OutputFile
    abstract val outputComponent: RegularFileProperty

    @TaskAction
    fun assemble() {
        val prod = prodDir.get().asFile.apply { mkdirs() }
        val coreWasm = prod.listFiles { f ->
            f.extension == "wasm" &&
                !f.name.endsWith(".component.wasm") &&
                !f.name.endsWith(".stub.wasm")
        }?.firstOrNull() ?: error("Production wasm not found in ${prod.absolutePath}")

        val intermediateDir = File(prod, "component-temp").apply { mkdirs() }
        val embedded = File(intermediateDir, coreWasm.nameWithoutExtension + ".embedded.wasm")
        val component = outputComponent.get().asFile
        val witWorkspace = File(witDir.get().asFile, "cli")
        require(witWorkspace.isDirectory) {
            "Expected WIT workspace under ${witWorkspace.absolutePath}; is the WASI bundle synced?"
        }

        execOps.exec {
            commandLine(
                "wasm-tools", "component", "embed",
                "--world", world.get(),
                witWorkspace.absolutePath,
                coreWasm.absolutePath,
                "-o", embedded.absolutePath,
            )
        }

        execOps.exec {
            commandLine(
                "wasm-tools", "component", "new",
                "--realloc-via-memory-grow",
                "--skip-validation",
                "-o", component.absolutePath,
                embedded.absolutePath,
            )
        }

        embedded.delete()
        if (intermediateDir.listFiles()?.isEmpty() == true) {
            intermediateDir.delete()
        }
    }
}

val assemblePreview2Component = tasks.register("assemblePreview2Component", AssemblePreview2Component::class.java) {
    // Ensure production core wasm exists and is patched before embedding metadata
    dependsOn("patchCanonicalAbiRealloc")
    dependsOn(":kotlin-stdlib:stageWasiPreviewWorkspace")
    world.set(wasiPreview2World)
    witDir.set(wasiPreview2WitDir)
    prodDir.set(layout.buildDirectory.dir("compileSync/wasmWasi/main/productionExecutable/kotlin"))
    // Predictable output name based on project name
    outputComponent.set(prodDir.map { d -> d.file("${project.name}.component.wasm") })
}

val validatePreview2Component = tasks.register("validatePreview2Component") {
    dependsOn("wasmWasiProductionExecutableValidateWasmComponent")
}

abstract class PrintPreview2ComponentWitTask @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory
    abstract val componentDir: DirectoryProperty

    @TaskAction
    fun printWit() {
        val prodDir = componentDir.get().asFile
        val component = prodDir.listFiles { f -> f.extension == "wasm" && f.name.endsWith(".component.wasm") }
            ?.firstOrNull()
            ?: error("Component wasm not found in ${prodDir.absolutePath}")
        execOps.exec {
            commandLine("wasm-tools", "component", "wit", component.absolutePath)
        }
    }
}

val printPreview2ComponentWit = tasks.register("printPreview2ComponentWit", PrintPreview2ComponentWitTask::class.java) {
    dependsOn(assemblePreview2Component)
    componentDir.set(layout.buildDirectory.dir("compileSync/wasmWasi/main/productionExecutable/kotlin"))
}

abstract class RunPreview2ComponentViaWasmtime @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory
    abstract val toolsDir: DirectoryProperty

    @get:InputDirectory
    abstract val componentDir: DirectoryProperty

    @get:org.gradle.api.tasks.OutputFile
    abstract val logFile: RegularFileProperty

    @TaskAction
    fun run() {
        // Discover the executable under toolsDir/**/wasmtime
        val tools = toolsDir.get().asFile
        val exe = tools.walkTopDown().maxDepth(4)
            .firstOrNull { f -> f.isFile && (f.name == "wasmtime" || f.name == "wasmtime.exe") }
            ?.absolutePath
            ?: error("Wasmtime executable not found under ${tools.absolutePath}; did setup run?")

        val prodDir = componentDir.get().asFile
        val componentFile = prodDir.listFiles { f -> f.extension == "wasm" && f.name.endsWith(".component.wasm") }
            ?.firstOrNull()
            ?: error("Component wasm not found in ${prodDir.absolutePath}. Did assemblePreview2Component run?")

        // Capture stdout+stderr to a single log file
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val log = logFile.get().asFile
        log.parentFile.mkdirs()

        // Execute: wasmtime run <component> with the component-model flag
        execOps.exec {
            commandLine(
                exe,
                "run",
                "--wasm", "component-model",
                "--wasm", "gc",
                "--wasm", "function-references",
                "--wasm", "exceptions",
                "--wasm", "reference-types",
                "--wasm", "bulk-memory",
                "--wasm", "multi-memory",
                "--wasm", "multi-value",
                "--wasm", "simd",
                componentFile.absolutePath,
            )
            standardOutput = out
            errorOutput = err
        }

        // Persist logs and surface stdout/stderr to the console for convenience
        log.writeText(buildString {
            appendLine("=== STDOUT ===")
            append(out.toString("UTF-8"))
            appendLine()
            appendLine("=== STDERR ===")
            append(err.toString("UTF-8"))
            appendLine()
        })
        val stdoutText = out.toString("UTF-8")
        val stderrText = err.toString("UTF-8")
        if (stdoutText.isNotEmpty()) println(stdoutText)
        if (stderrText.isNotEmpty()) System.err.println(stderrText)
        if (out.size() == 0 && err.size() > 0) {
            throw RuntimeException("Wasmtime run produced no stdout; see ${log.absolutePath}")
        }
    }
}

tasks.register("runPreview2ComponentViaWasmtime", RunPreview2ComponentViaWasmtime::class.java) {
    // Ensure component was assembled and Wasmtime installed
    dependsOn(assemblePreview2Component)
    dependsOn("kotlinWasmWasmtimeSetup")
    toolsDir.set(layout.buildDirectory.dir("tools/wasmtime"))
    componentDir.set(layout.buildDirectory.dir("compileSync/wasmWasi/main/productionExecutable/kotlin"))
    logFile.set(layout.buildDirectory.file("runLogs/preview2-wasmtime.log"))
}

// Patch the core wasm to fix the early-return if shape in canonical_abi_realloc
abstract class PatchCanonicalAbiRealloc @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory
    abstract val coreDir: DirectoryProperty

    @TaskAction
    fun patch() {
        val buildDir = coreDir.get().asFile
        val wasm = buildDir.listFiles { f -> f.extension == "wasm" && !f.name.endsWith(".component.wasm") && !f.name.endsWith(".stub.wasm") }?.firstOrNull()
            ?: return
        val wat = File(buildDir, wasm.nameWithoutExtension + ".wat")
        // Dump to WAT
        fun runCmd(vararg args: String) {
            val proc = ProcessBuilder(args.toList()).inheritIO().start()
            val code = proc.waitFor()
            if (code != 0) throw RuntimeException("Command failed: ${args.joinToString(" ")}")
        }
        runCmd("wasm-tools", "print", wasm.absolutePath, "-o", wat.absolutePath)
        var text = wat.readText()
        val marker = "(func ${'$'}canonical_abi_realloc"
        val idx = text.indexOf(marker)
        if (idx >= 0) {
            val end = text.indexOf("global.get ${'$'}_cabi_heap_end", idx).let { if (it < 0) idx + 4096 else it }
            val body = text.substring(idx, end)
            val patchedBody = Regex("memory\\.grow(?!\\s*\\n\\s*drop)")
                .replace(body) { match -> "${match.value}\n        drop" }
            if (patchedBody != body) {
                text = text.substring(0, idx) + patchedBody + text.substring(end)
            }
        }
        if (text != wat.readText()) {
            wat.writeText(text)
            runCmd("wasm-tools", "parse", wat.absolutePath, "-o", wasm.absolutePath)
        }
    }
}

tasks.register("patchCanonicalAbiRealloc", PatchCanonicalAbiRealloc::class.java) {
    dependsOn("compileProductionExecutableKotlinWasmWasi")
    coreDir.set(layout.buildDirectory.dir("compileSync/wasmWasi/main/productionExecutable/kotlin"))
}

// Convenience: run the core wasm via Wasmtime (bypassing component wrapper), configuration-cache safe
abstract class RunCoreWasmViaWasmtime @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory
    abstract val toolsDir: DirectoryProperty

    @get:InputDirectory
    abstract val coreDir: DirectoryProperty

    @TaskAction
    fun run() {
        val tools = toolsDir.get().asFile
        val exe = tools.walkTopDown().maxDepth(4)
            .firstOrNull { f -> f.isFile && (f.name == "wasmtime" || f.name == "wasmtime.exe") }
            ?.absolutePath
            ?: error("Wasmtime executable not found under ${tools.absolutePath}; did setup run?")
        val dir = coreDir.get().asFile
        val wasm = dir.listFiles { f -> f.extension == "wasm" && !f.name.endsWith(".stub.wasm") && !f.name.endsWith(".component.wasm") }
            ?.firstOrNull() ?: error("Core wasm not found in ${dir.absolutePath}")
        execOps.exec {
            workingDir = wasm.parentFile
            commandLine(
                exe,
                "-W", "gc=y",
                "-W", "reference-types=y",
                "-W", "multi-memory=y",
                "-W", "bulk-memory=y",
                "-W", "multi-value=y",
                "-W", "simd=y",
                "-W", "exceptions=y",
                "-W", "function-references=y",
                wasm.name
            )
        }
    }
}

tasks.register("runCoreWasmViaWasmtime", RunCoreWasmViaWasmtime::class.java) {
    // Ensure Wasmtime installed and core wasm built
    val setupName = extensions.extraProperties.get("wasmtimeSetupTaskName").toString()
    dependsOn(setupName, "compileProductionExecutableKotlinWasmWasi")
    toolsDir.set(layout.buildDirectory.dir("tools/wasmtime"))
    coreDir.set(layout.buildDirectory.dir("compileSync/wasmWasi/main/productionExecutable/kotlin"))
}
