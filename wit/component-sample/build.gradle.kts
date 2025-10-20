import org.gradle.api.DefaultTask
import org.gradle.api.attributes.Attribute
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.targets.wasm.component.component
import org.jetbrains.kotlin.gradle.targets.wasm.wasmtime.wasmtime
import java.io.ByteArrayOutputStream
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
            dependencies { implementation(kotlin("stdlib")) }
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
val assemblePreview2Component = tasks.register("assemblePreview2Component", org.gradle.api.tasks.Exec::class.java) {
    // Disable the default helper task if present (older CLI flags)
    tasks.matching { it.name == "wasmWasiProductionExecutableAssembleWasmComponent" }.configureEach { it.enabled = false }
    // Ensure production core wasm exists
    dependsOn("compileProductionExecutableKotlinWasmWasi")
    val wasm = layout.buildDirectory.file("compileSync/wasmWasi/main/productionExecutable/kotlin/${project.name}.wasm")
    val out = layout.buildDirectory.file("compileSync/wasmWasi/main/productionExecutable/kotlin/${project.name}.component.wasm")
    doFirst {
        out.get().asFile.parentFile.mkdirs()
        commandLine("wasm-tools", "component", "new", "-o", out.get().asFile.absolutePath, wasm.get().asFile.absolutePath)
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
        // Ensure wasmtime is installed (the Wasmtime plugin exposes the setup task name via extra)
        val setupName = project.extensions.extraProperties.get("wasmtimeSetupTaskName").toString()
        project.tasks.named(setupName).get()

        val rawProvider = project.extensions.extraProperties["wasmtimeExecutableProvider"]
            ?: error("wasmtimeExecutableProvider not injected by Wasmtime plugin")
        val exeProvider = rawProvider as? Provider<*>
            ?: error("Unexpected provider type for wasmtimeExecutableProvider: ${rawProvider::class.java}")
        val exe = exeProvider.get()?.toString()
            ?: error("Wasmtime executable provider returned null")

        val componentFile = project.layout.buildDirectory.file(
            "compileSync/wasmWasi/main/productionExecutable/kotlin/${project.name}.component.wasm"
        ).get().asFile

        // Capture stdout+stderr to a single log file
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val logFile = project.layout.buildDirectory.file("runLogs/preview2-wasmtime.log").get().asFile
        logFile.parentFile.mkdirs()

        // Execute: wasmtime component run <component>
        execOps.exec {
            commandLine(exe, "component", "run", componentFile.absolutePath)
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
}
