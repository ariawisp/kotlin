import org.gradle.api.DefaultTask
import org.gradle.api.attributes.Attribute
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskAction
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
            witDir.set(layout.projectDirectory.dir("../../libraries/stdlib/wasm/wasi/wit-upstream"))
            world.set("wasi:cli/run")
            importMemory.convention(false)
        }
    }

    sourceSets {
        val wasmWasiMain by getting {
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

val assemblePreview2Component = tasks.register("assemblePreview2Component", org.gradle.api.tasks.Exec::class.java) {
    // Ensure production core wasm exists and is patched
    dependsOn("patchCanonicalAbiRealloc")
    val prodDir = layout.buildDirectory.dir("compileSync/wasmWasi/main/productionExecutable/kotlin")
    doFirst {
        val dir = prodDir.get().asFile
        dir.mkdirs()
        val wasm = dir.listFiles { f -> f.extension == "wasm" && !f.name.endsWith(".component.wasm") && !f.name.endsWith(".stub.wasm") }
            ?.firstOrNull()
            ?: error("Production wasm not found in ${dir.absolutePath}")
        val out = File(dir, wasm.nameWithoutExtension + ".component.wasm")
        commandLine(
            "wasm-tools", "component", "new",
            "--realloc-via-memory-grow",
            "--skip-validation",
            "-o", out.absolutePath,
            wasm.absolutePath
        )
    }
}

val validatePreview2Component = tasks.register("validatePreview2Component") {
    dependsOn("wasmWasiProductionExecutableValidateWasmComponent")
}

val printPreview2ComponentWit = tasks.register("printPreview2ComponentWit") {
    dependsOn("wasmWasiProductionExecutablePrintComponentWit")
}

abstract class RunPreview2ComponentViaWasmtime @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @TaskAction
    fun run() {
        // Ensure Wasmtime is installed via the known setup task
        project.tasks.named("kotlinWasmWasmtimeSetup").get()
        // Discover the executable under build/tools/wasmtime/**/wasmtime
        val exe = run {
            val tools = project.layout.buildDirectory.dir("tools/wasmtime").get().asFile
            val candidates = tools.walkTopDown().maxDepth(4).filter { f -> f.isFile && (f.name == "wasmtime" || f.name == "wasmtime.exe") }.toList()
            (candidates.firstOrNull() ?: error("Wasmtime executable not found under ${tools.absolutePath}; did setup run?"))
        }.absolutePath

        val prodDir = project.layout.buildDirectory.dir("compileSync/wasmWasi/main/productionExecutable/kotlin").get().asFile
        val componentFile = prodDir.listFiles { f -> f.extension == "wasm" && f.name.endsWith(".component.wasm") }
            ?.firstOrNull()
            ?: error("Component wasm not found in ${prodDir.absolutePath}. Did assemblePreview2Component run?")

        // Capture stdout+stderr to a single log file
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val logFile = project.layout.buildDirectory.file("runLogs/preview2-wasmtime.log").get().asFile
        logFile.parentFile.mkdirs()

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

        // Persist logs and do a minimal assertion on output presence
        logFile.writeText(buildString {
            appendLine("=== STDOUT ===")
            append(out.toString("UTF-8"))
            appendLine()
            appendLine("=== STDERR ===")
            append(err.toString("UTF-8"))
            appendLine()
        })
        if (out.size() == 0 && err.size() > 0) {
            throw RuntimeException("Wasmtime run produced no stdout; see ${logFile.absolutePath}")
        }
    }
}

tasks.register("runPreview2ComponentViaWasmtime", RunPreview2ComponentViaWasmtime::class.java) {
    // Ensure component was assembled and Wasmtime installed
    dependsOn(assemblePreview2Component)
    dependsOn("kotlinWasmWasmtimeSetup")
    notCompatibleWithConfigurationCache("Executes external process dynamically")
}

// Patch the core wasm to fix the early-return if shape in canonical_abi_realloc
abstract class PatchCanonicalAbiRealloc @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @TaskAction
    fun patch() {
        val buildDir = project.layout.buildDirectory.dir("compileSync/wasmWasi/main/productionExecutable/kotlin").get().asFile
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
        val text = wat.readText()
        // Replace the first 'if (result i32)' after the i32.eqz in canonical_abi_realloc with a plain 'if'
        val marker = "(func ${'$'}canonical_abi_realloc"
        val idx = text.indexOf(marker)
        if (idx >= 0) {
            val end = text.indexOf("global.get ${'$'}_cabi_heap_end", idx).let { if (it < 0) idx + 4096 else it }
            // Compute a safe patch window for logging/debug if needed
            val needle = "if (result i32)"
            val pos = text.indexOf(needle, idx)
           val bodyPatched = if (pos >= 0 && pos < end) {
                val replacedIf = text.substring(0, pos) + "if" + text.substring(pos + needle.length)
                if ("memory.grow" in replacedIf) {
                    replacedIf.replace("memory.grow", "memory.grow\n        drop")
                } else {
                    replacedIf
                }
            } else text
            if (bodyPatched != text) {
                wat.writeText(bodyPatched)
                // Re-assemble back to wasm
                runCmd("wasm-tools", "parse", wat.absolutePath, "-o", wasm.absolutePath)
            }
        }
    }
}

tasks.register("patchCanonicalAbiRealloc", PatchCanonicalAbiRealloc::class.java) {
    dependsOn("compileProductionExecutableKotlinWasmWasi")
    notCompatibleWithConfigurationCache("Performs dynamic file IO using project APIs at execution time")
}

// Convenience: run the core wasm via Wasmtime (bypassing component wrapper)
tasks.register("runCoreWasmViaWasmtime", org.gradle.api.tasks.Exec::class.java) {
    // Ensure Wasmtime installed and core wasm built
    val setupName = project.extensions.extraProperties.get("wasmtimeSetupTaskName").toString()
    dependsOn(setupName, "compileProductionExecutableKotlinWasmWasi")
    doFirst {
        val exe = run {
            val tools = project.layout.buildDirectory.dir("tools/wasmtime").get().asFile
            val candidates = tools.walkTopDown().maxDepth(4).filter { f -> f.isFile && (f.name == "wasmtime" || f.name == "wasmtime.exe") }.toList()
            (candidates.firstOrNull() ?: error("Wasmtime executable not found under ${tools.absolutePath}; did setup run?"))
        }.absolutePath
        val dir = project.layout.buildDirectory.dir("compileSync/wasmWasi/main/productionExecutable/kotlin").get().asFile
        val wasm = dir.listFiles { f -> f.extension == "wasm" && !f.name.endsWith(".stub.wasm") && !f.name.endsWith(".component.wasm") }
            ?.firstOrNull() ?: error("Core wasm not found in ${dir.absolutePath}")
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
