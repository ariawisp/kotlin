import org.gradle.internal.os.OperatingSystem
import java.net.URI
import com.github.gradle.node.npm.task.NpmTask
import java.nio.file.Files
import java.util.*
import java.io.File
import javax.inject.Inject
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty
// Gradle Provider API
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

plugins {
    kotlin("jvm")
    id("jps-compatible")
    alias(libs.plugins.gradle.node)
    id("d8-configuration")
    id("binaryen-configuration")
    id("nodejs-configuration")
    id("java-test-fixtures")
    id("project-tests-convention")
}

node {
    download.set(true)
    version.set(nodejsVersion)
    nodeProjectDir.set(layout.buildDirectory.dir("node"))
}

repositories {
    ivy {
        url = URI("https://archive.mozilla.org/pub/firefox/releases/")
        patternLayout {
            artifact("[revision]/jsshell/[artifact]-[classifier].[ext]")
        }
        metadataSources { artifact() }
        content { includeModule("org.mozilla", "jsshell") }
    }
    ivy {
        url = URI("https://github.com/WasmEdge/WasmEdge/releases/download/")
        patternLayout {
            artifact("[revision]/WasmEdge-[revision]-[classifier].[ext]")
        }
        metadataSources { artifact() }
        content { includeModule("org.wasmedge", "wasmedge") }
    }
    ivy {
        url = URI("https://github.com/bytecodealliance/wasmtime/releases/download/")
        patternLayout {
            artifact("v[revision]/wasmtime-v[revision]-[classifier].[ext]")
        }
        metadataSources { artifact() }
        content { includeModule("dev.wasmtime", "wasmtime") }
    }
    ivy {
        url = URI("https://packages.jetbrains.team/files/p/kt/kotlin-file-dependencies/javascriptcore/")
        patternLayout {
            artifact("[classifier]_[revision].zip")
        }
        metadataSources { artifact() }
        content { includeModule("org.jsc", "jsc") }
    }
}

enum class OsName { WINDOWS, MAC, LINUX, UNKNOWN }
enum class OsArch { X86_32, X86_64, ARM64, UNKNOWN }
data class OsType(val name: OsName, val arch: OsArch)


abstract class CreateJscRunner : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Input
    abstract val osTypeName: Property<OsName>

    @TaskAction
    fun action() {
        val jscBinariesDir = inputDirectory.get().asFile.let { dir ->
            when (osTypeName.get()) {
                OsName.MAC -> dir.resolve("Release")
                OsName.LINUX -> dir
                OsName.WINDOWS -> dir.resolve("bin")
                else -> error("unsupported os name")
            }
        }

        val runnerContent = getJscRunnerContent(jscBinariesDir, osTypeName.get())
        val outputFile = outputFile.get().asFile
        with(outputFile) {
            writeText(runnerContent)
            setExecutable(true)
        }
    }

    fun getJscRunnerContent(jscBinariesDir: File, osTypeName: OsName) = when (osTypeName) {
        OsName.MAC ->
            """#!/usr/bin/env bash
DYLD_FRAMEWORK_PATH="$jscBinariesDir" DYLD_LIBRARY_PATH="$jscBinariesDir" "$jscBinariesDir/jsc" "$@"
"""
        OsName.LINUX ->
            """#!/usr/bin/env bash
LD_LIBRARY_PATH="$jscBinariesDir/lib" exec "$jscBinariesDir/lib/ld-linux-x86-64.so.2" "$jscBinariesDir/bin/jsc" "$@"
"""
        OsName.WINDOWS ->
            """@echo off
"$jscBinariesDir\\jsc.exe" %*
"""
        else -> error("unsupported os type $osTypeName")
    }
}

val currentOsType = run {
    val gradleOs = OperatingSystem.current()
    val osName = when {
        gradleOs.isMacOsX -> OsName.MAC
        gradleOs.isWindows -> OsName.WINDOWS
        gradleOs.isLinux -> OsName.LINUX
        else -> OsName.UNKNOWN
    }

    val osArch = when (providers.systemProperty("sun.arch.data.model").get()) {
        "32" -> OsArch.X86_32
        "64" -> when (providers.systemProperty("os.arch").get().lowercase()) {
            "aarch64" -> OsArch.ARM64
            else -> OsArch.X86_64
        }
        else -> OsArch.UNKNOWN
    }

    OsType(osName, osArch)
}

val jsShellVersion = libs.versions.jsShell
val jsShellSuffix = when (currentOsType) {
    OsType(OsName.LINUX, OsArch.X86_32) -> "linux-i686"
    OsType(OsName.LINUX, OsArch.X86_64) -> "linux-x86_64"
    OsType(OsName.MAC, OsArch.X86_64),
    OsType(OsName.MAC, OsArch.ARM64) -> "mac"
    OsType(OsName.WINDOWS, OsArch.X86_32) -> "win32"
    OsType(OsName.WINDOWS, OsArch.X86_64) -> "win64"
    else -> error("unsupported os type $currentOsType")
}

val jsShell by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

val wasmEdgeVersion = libs.versions.wasmedge
val wasmEdgeSuffix = when (currentOsType) {
    OsType(OsName.LINUX, OsArch.X86_64) -> "manylinux_2_28_x86_64@tar.gz"
    OsType(OsName.MAC, OsArch.X86_64) -> "darwin_x86_64@tar.gz"
    OsType(OsName.MAC, OsArch.ARM64) -> "darwin_arm64@tar.gz"
    OsType(OsName.WINDOWS, OsArch.X86_32),
    OsType(OsName.WINDOWS, OsArch.X86_64) -> "windows@zip"
    else -> error("unsupported os type $currentOsType")
}
val wasmEdgeInnerSuffix = when (currentOsType.name) {
    OsName.LINUX -> "Linux"
    OsName.MAC -> "Darwin"
    OsName.WINDOWS -> "Windows"
    else -> error("unsupported os type $currentOsType")
}

val wasmEdge by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

val wasmtimeVersion = libs.versions.wasmtime
val wasmtimePlatformSuffix = when (currentOsType) {
    OsType(OsName.LINUX, OsArch.X86_64) -> "x86_64-linux"
    OsType(OsName.MAC, OsArch.X86_64) -> "x86_64-macos"
    OsType(OsName.MAC, OsArch.ARM64) -> "aarch64-macos"
    OsType(OsName.WINDOWS, OsArch.X86_32),
    OsType(OsName.WINDOWS, OsArch.X86_64) -> "x86_64-windows"
    else -> error("unsupported os type $currentOsType")
}
val wasmtimeSuffix = wasmtimePlatformSuffix + "@" + when (currentOsType.name) {
    OsName.LINUX -> "tar.xz"
    OsName.MAC -> "tar.xz"
    OsName.WINDOWS -> "zip"
    else -> error("unsupported os type $currentOsType")
}

val wasmtime by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

val jscOsDependentVersion = when (currentOsType.name) {
    OsName.MAC -> libs.versions.jscSequoia
    OsName.LINUX -> libs.versions.jscLinux
    OsName.WINDOWS -> libs.versions.jscWindows
    else -> error("unsupported os type $currentOsType")
}.get()

//https://youtrack.jetbrains.com/articles/KT-A-950/JavaScript-Core-Update-instruction
val jscOsDependentClassifier = when (currentOsType.name) {
    OsName.MAC -> "sequoia"
    OsName.LINUX -> "linux64"
    OsName.WINDOWS -> "win64"
    else -> error("unsupported os type $currentOsType")
}

val jscOsDependentRevision = when (currentOsType.name) {
    OsName.MAC -> libs.versions.jscSequoia
    OsName.LINUX -> libs.versions.jscLinux
    OsName.WINDOWS -> libs.versions.jscWindows
    else -> error("unsupported os type $currentOsType")
}.get()


val jsc by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    testFixturesApi(testFixtures(project(":compiler:tests-common")))
    testFixturesApi(testFixtures(project(":compiler:tests-common-new")))
    testFixturesApi(testFixtures(project(":js:js.tests")))
    testFixturesApi(intellijCore())
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)

    jsShell("org.mozilla:jsshell:${jsShellVersion.get()}:$jsShellSuffix@zip")

    implicitDependencies("org.mozilla:jsshell:${jsShellVersion.get()}:win64@zip")
    implicitDependencies("org.mozilla:jsshell:${jsShellVersion.get()}:linux-x86_64@zip")
    implicitDependencies("org.mozilla:jsshell:${jsShellVersion.get()}:mac@zip")

    wasmEdge("org.wasmedge:wasmedge:${wasmEdgeVersion.get()}:$wasmEdgeSuffix")

    implicitDependencies("org.wasmedge:wasmedge:${wasmEdgeVersion.get()}:windows@zip")
    implicitDependencies("org.wasmedge:wasmedge:${wasmEdgeVersion.get()}:manylinux_2_28_x86_64@tar.gz")
    implicitDependencies("org.wasmedge:wasmedge:${wasmEdgeVersion.get()}:darwin_arm64@tar.gz")

    wasmtime("dev.wasmtime:wasmtime:${wasmtimeVersion.get()}:$wasmtimeSuffix")

    implicitDependencies("dev.wasmtime:wasmtime:${wasmtimeVersion.get()}:x86_64-windows@zip")
    implicitDependencies("dev.wasmtime:wasmtime:${wasmtimeVersion.get()}:x86_64-linux@tar.xz")
    implicitDependencies("dev.wasmtime:wasmtime:${wasmtimeVersion.get()}:aarch64-macos@tar.xz")

    jsc("org.jsc:jsc:$jscOsDependentRevision:$jscOsDependentClassifier")

    implicitDependencies("org.jsc:jsc:${libs.versions.jscSequoia.get()}:sequoia")
    implicitDependencies("org.jsc:jsc:${libs.versions.jscLinux.get()}:linux64")
    implicitDependencies("org.jsc:jsc:${libs.versions.jscWindows.get()}:win64")
}

optInToExperimentalCompilerApi()

sourceSets {
    "main" { }
    "test" {
        projectDefault()
        generatedTestDir()
    }
    "testFixtures" { projectDefault() }
}

fun Test.setupWasmStdlib(target: String) {
    @Suppress("LocalVariableName")
    val Target = target.capitalize()
    dependsOn(":kotlin-stdlib:compileKotlinWasm$Target")
    systemProperty("kotlin.wasm-$target.stdlib.path", "libraries/stdlib/build/classes/kotlin/wasm$Target/main")
    dependsOn(":kotlin-test:compileKotlinWasm$Target")
    systemProperty("kotlin.wasm-$target.kotlin.test.path", "libraries/kotlin.test/build/classes/kotlin/wasm$Target/main")
}

fun Test.setupGradlePropertiesForwarding() {
    val rootLocalProperties = Properties().apply {
        rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use {
            load(it)
        }
    }

    val allProperties = properties + rootLocalProperties

    val prefixForPropertiesToForward = "fd."
    for ((key, value) in allProperties) {
        if (key is String && key.startsWith(prefixForPropertiesToForward)) {
            systemProperty(key.substring(prefixForPropertiesToForward.length), value!!)
        }
    }
}

val testDataDir = project(":js:js.translator").projectDir.resolve("testData")
val typescriptTestsDir = testDataDir.resolve("typescript-export")
val wasmTestDir = typescriptTestsDir.resolve("wasm")
val toolsDirectory = layout.buildDirectory.dir("tools")

fun generateTypeScriptTestFor(dir: String): TaskProvider<NpmTask> = tasks.register<NpmTask>("generate-ts-for-$dir") {
    val baseDir = wasmTestDir.resolve(dir)
    val mainTsFile = fileTree(baseDir).files.find { it.name.endsWith("__main.ts") } ?: return@register
    val mainJsFile = baseDir.resolve("${mainTsFile.nameWithoutExtension}.js")

    workingDir.set(testDataDir)

    inputs.file(mainTsFile)
    outputs.file(mainJsFile)
    outputs.upToDateWhen { mainJsFile.exists() }

    args.set(listOf("run", "generateTypeScriptTests", "--", "./typescript-export/wasm/$dir/tsconfig.json"))
}

val installTsDependencies by task<NpmTask> {
    val packageLockFile = testDataDir.resolve("package-lock.json")
    val nodeModules = testDataDir.resolve("node_modules")
    inputs.file(testDataDir.resolve("package.json"))
    inputs.file(packageLockFile)
    outputs.upToDateWhen { nodeModules.exists() }

    workingDir.set(testDataDir)
    npmCommand.set(listOf("ci"))
}

val generateTypeScriptTests by parallel(
    beforeAll = installTsDependencies,
    tasksToRun = wasmTestDir
        .listFiles { it: File -> it.isDirectory }
        .map { generateTypeScriptTestFor(it.name) }
)


val jsShellDirectory = toolsDirectory.map { it.dir("JsShell").asFile }
val jsShellUnpackedDirectory = jsShellDirectory.map { it.resolve("jsshell-$jsShellSuffix-${jsShellVersion.get()}") }
val unzipJsShell by task<Copy> {
    dependsOn(jsShell)
    from {
        zipTree(jsShell.singleFile)
    }
    into(jsShellUnpackedDirectory)
}

val wasmEdgeDirectory = toolsDirectory.map { it.dir("WasmEdge").asFile }
val wasmEdgeDirectoryName = wasmEdgeVersion.map { version -> "WasmEdge-$version-$wasmEdgeInnerSuffix" }
val wasmEdgeUnpackedDirectory = wasmEdgeDirectory.map { it.resolve(wasmEdgeDirectoryName.get()) }
val unzipWasmEdge by task<Copy> {
    dependsOn(wasmEdge)

    val wasmEdgeDirectory = wasmEdgeDirectory
    val currentOsTypeForConfigurationCache = currentOsType.name
    val wasmEdgeUnpackedDirectory = wasmEdgeUnpackedDirectory

    from {
        if (wasmEdge.singleFile.extension == "zip") {
            zipTree(wasmEdge.singleFile)
        } else {
            tarTree(wasmEdge.singleFile)
        }
    }
    into(wasmEdgeDirectory)
    inputs.property("currentOsTypeForConfigurationCache", currentOsTypeForConfigurationCache)

    doLast {
        if (currentOsTypeForConfigurationCache !in setOf(OsName.MAC, OsName.LINUX)) return@doLast

        val unpackedWasmEdgeDirectory = wasmEdgeUnpackedDirectory.get().toPath()

        val libDirectory = unpackedWasmEdgeDirectory
            .resolve(if (currentOsTypeForConfigurationCache == OsName.MAC) "lib" else "lib64")

        val targets = if (currentOsTypeForConfigurationCache == OsName.MAC)
            listOf("libwasmedge.0.1.0.dylib", "libwasmedge.0.1.0.tbd")
        else listOf("libwasmedge.so.0.1.0")

        targets.forEach {
            val target = libDirectory.resolve(it)
            val firstLink = libDirectory.resolve(it.replace("0.1.0", "0")).also(Files::deleteIfExists)
            val secondLink = libDirectory.resolve(it.replace(".0.1.0", "")).also(Files::deleteIfExists)

            Files.createSymbolicLink(firstLink, target)
            Files.createSymbolicLink(secondLink, target)
        }
    }
}


val jscDirectory = toolsDirectory.map { it.dir("JavaScriptCore").asFile }
val jscUnpackedDirectory = jscDirectory.map { it.resolve("jsc-$jscOsDependentClassifier-$jscOsDependentRevision") }
val unzipJsc by task<Copy> {
    dependsOn(jsc)
    from { zipTree(jsc.singleFile) }

    val jscUnpackedDirectory = jscUnpackedDirectory
    into(jscUnpackedDirectory)

    val isLinux = currentOsType.name == OsName.LINUX
    inputs.property("isLinux", isLinux)

    doLast {
        if (isLinux) {
            val libDirectory = File(jscUnpackedDirectory.get(), "lib")
            for (file in libDirectory.listFiles()) {
                if (file.isFile && file.length() < 100) { // seems unpacked file link
                    val linkTo = file.readText()
                    file.delete()
                    Files.createSymbolicLink(file.toPath(), File(linkTo).toPath())
                }
            }
        }
    }
}

val createJscRunner by task<CreateJscRunner> {
    osTypeName.set(currentOsType.name)

    val runnerFileName = if (currentOsType.name == OsName.WINDOWS) "runJsc.cmd" else "runJsc"
    val runnerFilePath = jscDirectory.map { it.resolve(runnerFileName) }
    outputFile.fileProvider(runnerFilePath)

    inputDirectory.fileProvider(unzipJsc.map { it.outputs.files.singleFile })
}

val wasmtimeExtractDir = layout.buildDirectory.dir("tools/Wasmtime-${wasmtimeVersion.get()}-$wasmtimePlatformSuffix")

abstract class UnpackWasmtime : DefaultTask() {
    @get:Inject abstract val execOps: ExecOperations
    @get:InputFile abstract val archive: RegularFileProperty
    @get:OutputDirectory abstract val destDir: DirectoryProperty

    @TaskAction
    fun run() {
        val archiveFile = archive.get().asFile
        val dest = destDir.get().asFile
        dest.mkdirs()
        if (archiveFile.extension == "zip") {
            project.copy {
                from(project.zipTree(archiveFile))
                into(dest)
            }
        } else {
            // Expect tar.xz; use system tar with -J (xz) support
            execOps.exec {
                commandLine("tar", "-xJf", archiveFile.absolutePath, "-C", dest.absolutePath)
            }
        }
    }
}

val unzipWasmtime by tasks.registering(UnpackWasmtime::class) {
    archive.set(layout.file(providers.provider { wasmtime.singleFile }))
    destDir.set(wasmtimeExtractDir)
}

fun Test.setupSpiderMonkey() {
    val jsShellExecutablePath = unzipJsShell
        .map { it.outputs.files.singleFile }
        .map { it.resolve("js").absolutePath }

    jvmArgumentProviders += objects.newInstance<SystemPropertyClasspathProvider>().apply {
        classpath.from(jsShellExecutablePath)
        property.set("javascript.engine.path.SpiderMonkey")
    }
}

fun Test.setupWasmEdge() {
    val wasmEdgeExec = unzipWasmEdge
        .map { it.destinationDir.resolve(wasmEdgeDirectoryName.get()) }
        .map { it.resolve("bin/wasmedge") }

    inputs.file(wasmEdgeExec)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("wasmEdgeExecutable")

    jvmArgumentProviders += objects.newInstance<SystemPropertyClasspathProvider>().apply {
        classpath.from(wasmEdgeExec)
        property.set("wasm.engine.path.WasmEdge")
    }
}

fun Test.setupJsc() {
    val jscRunnerExecutablePath = createJscRunner
        .map { it.outputFile.asFile.get() }
        .map { it.absolutePath }

    jvmArgumentProviders += objects.newInstance<SystemPropertyClasspathProvider>().apply {
        classpath.from(jscRunnerExecutablePath)
        property.set("javascript.engine.path.JavaScriptCore")
    }
}

fun Test.setupWasmtime() {
    dependsOn(unzipWasmtime)
    val wasmtimeRoot = wasmtimeExtractDir.map { it.asFile }

    // Path like: <build>/tools/Wasmtime-<ver>-<platform>/wasmtime-v<ver>-<platform>/wasmtime
    val binProvider = wasmtimeRoot.map { File(it, "wasmtime-v${wasmtimeVersion.get()}-$wasmtimePlatformSuffix/wasmtime") }

    inputs.file(binProvider)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("wasmtimeBinary")

    jvmArgumentProviders.add { listOf("-Dwasm.engine.path.Wasmtime=${binProvider.get()}") }
}

testsJar {}

projectTests {
    testData(project(":compiler").isolated, "testData/debug")
    testData(project(":compiler").isolated, "testData/diagnostics")
    testData(project(":compiler").isolated, "testData/codegen")
    testData(project(":compiler").isolated, "testData/ir")
    testData(project(":compiler").isolated, "testData/klib")
    testData(project(":js:js.translator").isolated, "testData/box")
    testData(project(":js:js.translator").isolated, "testData/incremental")
    testData(project(":js:js.translator").isolated, "testData/typescript-export")

    testGenerator("org.jetbrains.kotlin.generators.tests.GenerateWasmTestsKt")

    val onlyWasmtime = providers.gradleProperty("kotlin.wasm.tests.onlyWasmtime").map { it.toBoolean() }.orElse(true)

    fun wasmProjectTest(taskName: String, skipInLocalBuild: Boolean = false, body: Test.() -> Unit = {}) {
        testTask(
            taskName = taskName,
            jUnitMode = JUnitMode.JUnit5,
            skipInLocalBuild = skipInLocalBuild,
        ) {
            workingDir = rootDir
            if (!onlyWasmtime.get()) {
                with(d8KotlinBuild) { setupV8() }
                with(nodeJsKotlinBuild) { setupNodeJs(nodejsVersion) }
                with(binaryenKotlinBuild) { setupBinaryen() }
                setupSpiderMonkey()
                setupWasmEdge()
                setupJsc()
            }
            setupWasmtime()
            useJUnitPlatform()
            if (!onlyWasmtime.get()) setupWasmStdlib("js")
            setupWasmStdlib("wasi")
            setupGradlePropertiesForwarding()
            val buildDirectory = layout.buildDirectory.map { "${it.asFile}/" }
            jvmArgumentProviders += objects.newInstance<SystemPropertyClasspathProvider>().apply {
                classpath.from(buildDirectory)
                property.set("kotlin.wasm.test.root.out.dir")
            }
            body()
        }
    }

    // Test everything (default). When onlyWasmtime is true, limit to WASI tests to avoid JS/other VMs.
    wasmProjectTest("test") {
        if (!onlyWasmtime.get()) {
            dependsOn(generateTypeScriptTests)
            include("**/*.class")
        } else {
            include("**/*WasmWasi*.class")
        }
    }

    wasmProjectTest("diagnosticTest", skipInLocalBuild = true) {
        include("**/Diagnostics*.class")
    }
}

// Optional Wasm Component Model helpers (no-op unless explicitly configured)
// Usage examples:
//   ./gradlew :wasm:wasm.tests:assembleWasmComponent -PwasmInput=/path/to/in.wasm -PcomponentOut=/path/to/out.component.wasm
//   ./gradlew :wasm:wasm.tests:validateWasmComponent -PcomponentIn=/path/to/out.component.wasm

abstract class AssembleWasmComponent @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val wasmInput: RegularFileProperty

    @get:OutputFile
    abstract val componentOut: RegularFileProperty

    @get:Input
    abstract val wasmToolsExecutable: Property<String>

    @get:Input
    @get:Optional
    abstract val reallocSymbol: Property<String>

    @get:Input
    @get:Optional
    abstract val postReturnSymbol: Property<String>

    @get:Input
    @get:Optional
    abstract val adapters: ListProperty<String>

    @TaskAction
    fun run() {
        val wasm = wasmInput.get().asFile
        val out = componentOut.get().asFile
        out.parentFile.mkdirs()
        val tool = wasmToolsExecutable.orNull ?: "wasm-tools"
        val realloc = reallocSymbol.orNull ?: "canonical_abi_realloc"
        val postRet = postReturnSymbol.orNull ?: "canonical_abi_post_return"

        // wasm-tools component new --realloc=... --post-return=... [--adapt X]* -o out input
        val cmd = mutableListOf(tool, "component", "new", "--realloc=$realloc", "--post-return=$postRet")
        adapters.orNull?.filter { it.isNotBlank() }?.forEach { a ->
            cmd += listOf("--adapt", a)
        }
        cmd += listOf("-o", out.absolutePath, wasm.absolutePath)
        execOps.exec { commandLine(cmd) }
    }
}

abstract class ValidateWasmComponent @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val componentIn: RegularFileProperty

    @get:Input
    abstract val wasmToolsExecutable: Property<String>

    @TaskAction
    fun run() {
        val tool = wasmToolsExecutable.orNull ?: "wasm-tools"
        val comp = componentIn.get().asFile
        execOps.exec {
            commandLine(tool, "validate", comp.absolutePath)
        }
    }
}

val assembleWasmComponent by tasks.registering(AssembleWasmComponent::class) {
    // Configure via -PwasmInput and optional -PcomponentOut
    val wasmInputProp = providers.gradleProperty("wasmInput")
    val componentOutProp = providers.gradleProperty("componentOut")
    val wasmPath = wasmInputProp.orNull ?: ""
    if (wasmPath.isNotBlank()) {
        wasmInput.set(layout.projectDirectory.file(wasmPath))
        val suggestedOut = componentOutProp.orNull ?: (File(wasmPath).let { f -> f.parentFile.resolve(f.nameWithoutExtension + ".component.wasm").absolutePath })
        componentOut.set(layout.projectDirectory.file(suggestedOut))
    }
    wasmToolsExecutable.set(providers.gradleProperty("wasm.tools.path").orElse("wasm-tools"))
    reallocSymbol.set(providers.gradleProperty("component.realloc.symbol").orElse("canonical_abi_realloc"))
    postReturnSymbol.set(providers.gradleProperty("component.postreturn.symbol").orElse("canonical_abi_post_return"))
    val adaptProp = providers.gradleProperty("component.adapt").orNull
    if (adaptProp != null) adapters.set(adaptProp.split(',').map { it.trim() }.filter { it.isNotEmpty() })

    onlyIf {
        if (!wasmInput.isPresent) {
            logger.lifecycle("assembleWasmComponent: specify -PwasmInput=/path/to/input.wasm")
            return@onlyIf false
        }
        true
    }
}

val validateWasmComponent by tasks.registering(ValidateWasmComponent::class) {
    val componentInProp = providers.gradleProperty("componentIn")
    val compPath = componentInProp.orNull ?: providers.gradleProperty("componentOut").orNull ?: ""
    if (compPath.isNotBlank()) {
        componentIn.set(layout.projectDirectory.file(compPath))
    }
    wasmToolsExecutable.set(providers.gradleProperty("wasm.tools.path").orElse("wasm-tools"))
    onlyIf {
        if (!componentIn.isPresent) {
            logger.lifecycle("validateWasmComponent: specify -PcomponentIn=/path/to/component.wasm")
            return@onlyIf false
        }
        true
    }
}

abstract class ValidateWit @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val witDir: DirectoryProperty

    @get:Input
    abstract val wasmToolsExecutable: Property<String>

    @TaskAction
    fun run() {
        val tool = wasmToolsExecutable.orNull ?: "wasm-tools"
        // Parse and pretty-print WIT to stdout; parse failure results in non-zero exit.
        execOps.exec {
            commandLine(tool, "component", "wit", witDir.get().asFile.absolutePath, "-t")
        }
    }
}

abstract class EmbedWitIntoCore @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val wasmInput: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val witDir: DirectoryProperty

    @get:OutputFile
    abstract val wasmOut: RegularFileProperty

    @get:Input
    abstract val wasmToolsExecutable: Property<String>

    @TaskAction
    fun run() {
        val tool = wasmToolsExecutable.orNull ?: "wasm-tools"
        val inFile = wasmInput.get().asFile
        val outFile = wasmOut.get().asFile
        outFile.parentFile.mkdirs()
        execOps.exec {
            commandLine(tool, "component", "embed", inFile.absolutePath, witDir.get().asFile.absolutePath, "-o", outFile.absolutePath)
        }
    }
}

abstract class PrintComponentWit @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val componentIn: RegularFileProperty

    @get:Input
    abstract val wasmToolsExecutable: Property<String>

    @TaskAction
    fun run() {
        val tool = wasmToolsExecutable.orNull ?: "wasm-tools"
        execOps.exec {
            commandLine(tool, "component", "wit", componentIn.get().asFile.absolutePath, "-t")
        }
    }
}

val validateWit by tasks.registering(ValidateWit::class) {
    val witPath = providers.gradleProperty("witPath").orNull ?: ""
    if (witPath.isNotBlank()) {
        witDir.set(layout.projectDirectory.dir(witPath))
    }
    wasmToolsExecutable.set(providers.gradleProperty("wasm.tools.path").orElse("wasm-tools"))
    onlyIf {
        if (!witDir.isPresent) {
            logger.lifecycle("validateWit: specify -PwitPath=/path/to/wit-root")
            return@onlyIf false
        }
        true
    }
}

val embedWitIntoCore by tasks.registering(EmbedWitIntoCore::class) {
    val wasmInputProp = providers.gradleProperty("wasmInput").orNull ?: ""
    val witPath = providers.gradleProperty("witPath").orNull ?: ""
    val wasmOutProp = providers.gradleProperty("wasmOut").orNull
    if (wasmInputProp.isNotBlank()) {
        wasmInput.set(layout.projectDirectory.file(wasmInputProp))
        val defaultOut = wasmOutProp ?: (File(wasmInputProp).let { f -> f.parentFile.resolve(f.nameWithoutExtension + ".with-wit.wasm").absolutePath })
        wasmOut.set(layout.projectDirectory.file(defaultOut))
    }
    if (witPath.isNotBlank()) {
        witDir.set(layout.projectDirectory.dir(witPath))
    }
    wasmToolsExecutable.set(providers.gradleProperty("wasm.tools.path").orElse("wasm-tools"))
    onlyIf {
        if (!wasmInput.isPresent || !witDir.isPresent) {
            logger.lifecycle("embedWitIntoCore: specify -PwasmInput=/path/to/in.wasm and -PwitPath=/path/to/wit-root")
            return@onlyIf false
        }
        true
    }
}

val printComponentWit by tasks.registering(PrintComponentWit::class) {
    val comp = providers.gradleProperty("componentIn").orNull ?: providers.gradleProperty("componentOut").orNull ?: ""
    if (comp.isNotBlank()) {
        componentIn.set(layout.projectDirectory.file(comp))
    }
    wasmToolsExecutable.set(providers.gradleProperty("wasm.tools.path").orElse("wasm-tools"))
    onlyIf {
        if (!componentIn.isPresent) {
            logger.lifecycle("printComponentWit: specify -PcomponentIn=/path/to/component.wasm")
            return@onlyIf false
        }
        true
    }
}
