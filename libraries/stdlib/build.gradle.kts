@file:Suppress("UNUSED_VARIABLE", "NAME_SHADOWING", "DEPRECATION")
import de.undercouch.gradle.tasks.download.Download
import org.jetbrains.kotlin.wit.gradle.WitCodegenTask
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.GenerateProjectStructureMetadata
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinUsages
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsTargetDsl
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinTargetWithNodeJsDsl
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinWasmTargetDsl
import org.jetbrains.kotlin.gradle.targets.js.KotlinWasmTargetAttribute
import org.jetbrains.kotlin.gradle.targets.js.KotlinWasmTargetType
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget
import org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompile
import org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile
import org.jetbrains.kotlin.gradle.tasks.UsesKotlinJavaToolchain
import org.jetbrains.kotlin.library.KOTLIN_JS_STDLIB_NAME
import org.jetbrains.kotlin.library.KOTLIN_WASM_STDLIB_NAME
import plugins.configureDefaultPublishing
import plugins.configureKotlinPomAttributes
import plugins.publishing.configureMultiModuleMavenPublishing
import plugins.publishing.copyAttributes
import kotlin.io.path.copyTo

plugins {
    kotlin("multiplatform")
    `maven-publish`
    signing
    id("nodejs-cache-redirector-configuration")
    id("d8-configuration")
    id("binaryen-configuration")
    id("de.undercouch.download")
    id("org.jetbrains.kotlin.wit.gradle")
}

description = "Kotlin Standard Library"

configureJvmToolchain(JdkMajorVersion.JDK_1_8)

fun resolvingConfiguration(name: String, configure: Action<Configuration> = Action {}) =
    configurations.create(name) {
        isCanBeResolved = true
        isCanBeConsumed = false
        configure(this)
    }
fun outgoingConfiguration(name: String, configure: Action<Configuration> = Action {}) =
    configurations.create(name) {
        isCanBeResolved = false
        isCanBeConsumed = true
        configure(this)
    }

fun KotlinCommonCompilerOptions.mainCompilationOptions() {
    languageVersion = KotlinVersion.KOTLIN_2_3
    apiVersion = KotlinVersion.KOTLIN_2_3
    freeCompilerArgs.add("-Xstdlib-compilation")
    freeCompilerArgs.add("-Xdont-warn-on-error-suppression")
    freeCompilerArgs.add("-Xcontext-parameters")
    if (!kotlinBuildProperties.disableWerror) allWarningsAsErrors = true
}

fun KotlinCommonCompilerOptions.addReturnValueCheckerInfo() {
    freeCompilerArgs.add("-Xreturn-value-checker=full")
}

val jvmBuiltinsRelativeDir = "libraries/stdlib/jvm/builtins"
val jvmBuiltinsDir = "${rootDir}/${jvmBuiltinsRelativeDir}"

val jsDir = "${projectDir}/js"
val jsBuiltinsSrcDir = "${layout.buildDirectory.get().asFile}/src/js-builtin-sources"

val commonOptIns = listOf(
    "kotlin.ExperimentalMultiplatform",
    "kotlin.contracts.ExperimentalContracts",
)
val commonTestOptIns = listOf(
    "kotlin.ExperimentalUnsignedTypes",
    "kotlin.ExperimentalStdlibApi",
    "kotlin.io.encoding.ExperimentalEncodingApi",
    "kotlin.uuid.ExperimentalUuidApi",
    "kotlin.time.ExperimentalTime",
)

kotlin {
    val renderDiagnosticNames by extra(project.kotlinBuildProperties.renderDiagnosticNames)
    val diagnosticNamesArg = if (renderDiagnosticNames) "-Xrender-internal-diagnostic-names" else null

    explicitApi()

    metadata {
        compilations {
            all {
                compileTaskProvider.configure {
                    compilerOptions {
                        freeCompilerArgs.set(
                            listOfNotNull(
                                "-Xallow-kotlin-package",
                                "-module-name", "kotlin-stdlib-common",
                                "-Xexpect-actual-classes",
                                "-Xexplicit-api=strict",
                                diagnosticNamesArg,
                            )
                        )
                        mainCompilationOptions()
                        addReturnValueCheckerInfo()
                    }
                }
            }
        }
    }
    jvm {
        withJava()
        compilations {
            val compileOnlyDeclarations by creating {
                compileTaskProvider.configure {
                    compilerOptions {
                        freeCompilerArgs.set(
                            listOfNotNull(
                                "-Xallow-kotlin-package",
                                "-Xsuppress-missing-builtins-error",
                                diagnosticNamesArg
                            )
                        )
                    }
                }
            }

            val main by getting {
                compileTaskProvider.configure {
                    // use os.arch as an input property of the compilation task
                    // to avoid resuing compilation results from the build cache
                    // produced on the other CPU architecture due to KT-53258
                    inputs.property("os.arch", providers.systemProperty("os.arch"))

                    this as UsesKotlinJavaToolchain
                    kotlinJavaToolchain.toolchain.use(getToolchainLauncherFor(JdkMajorVersion.JDK_11_0))
                    compilerOptions {
                        moduleName = "kotlin-stdlib"
                        jvmTarget = JvmTarget.JVM_1_8
                        // providing exhaustive list of args here
                        freeCompilerArgs.set(
                            listOfNotNull(
                                "-Xjdk-release=6",
                                "-jvm-default=disable",
                                "-Xallow-kotlin-package",
                                "-Xexpect-actual-classes",
                                "-Xmultifile-parts-inherit",
                                "-Xuse-14-inline-classes-mangling-scheme",
                                "-Xno-new-java-annotation-targets",
                                "-Xoutput-builtins-metadata",
                                "-Xcompile-builtins-as-part-of-stdlib",
                                diagnosticNamesArg
                            )
                        )
                        mainCompilationOptions()
                        addReturnValueCheckerInfo()
                    }
                }
                defaultSourceSet {
                    dependencies {
                        compileOnly(compileOnlyDeclarations.output.allOutputs)
                    }
                }
            }
            val mainJdk7 by creating {
                associateWith(main)
                compileTaskProvider.configure {
                    this as UsesKotlinJavaToolchain
                    kotlinJavaToolchain.toolchain.use(getToolchainLauncherFor(JdkMajorVersion.JDK_11_0))
                    compilerOptions {
                        moduleName = "kotlin-stdlib-jdk7"
                        jvmTarget = JvmTarget.JVM_1_8
                        freeCompilerArgs.set(
                            listOfNotNull(
                                "-Xjdk-release=7",
                                "-jvm-default=disable",
                                "-Xallow-kotlin-package",
                                "-Xexpect-actual-classes",
                                "-Xmultifile-parts-inherit",
                                "-Xno-new-java-annotation-targets",
                                "-Xexplicit-api=strict",
                                diagnosticNamesArg,
                            )
                        )
                        mainCompilationOptions()
                    }
                }
            }
            val mainJdk8 by creating {
                associateWith(main)
                associateWith(mainJdk7)
                compileTaskProvider.configure {
                    compilerOptions {
                        moduleName = "kotlin-stdlib-jdk8"
                        freeCompilerArgs.set(
                            listOfNotNull(
                                "-Xallow-kotlin-package",
                                "-jvm-default=disable",
                                "-Xmultifile-parts-inherit",
                                "-Xno-new-java-annotation-targets",
                                "-Xexplicit-api=strict",
                                diagnosticNamesArg,
                            )
                        )
                        mainCompilationOptions()
                    }
                }
            }
            project.sourceSets.create("java9") {
                java.srcDir("jvm/java9")
            }
            configureJava9Compilation("kotlin.stdlib", listOf(
                main.output.allOutputs,
                mainJdk7.output.allOutputs,
                mainJdk8.output.allOutputs,
            ), main.configurations.compileDependencyConfiguration)
            val test by getting {
                associateWith(mainJdk7)
                associateWith(mainJdk8)
                compileTaskProvider.configure {
                    compilerOptions {
                        freeCompilerArgs.addAll(
                            listOf(
                                "-Xallow-kotlin-package", // TODO: maybe rename test packages
                                "-Xexpect-actual-classes",
                            )
                        )
                    }
                }
            }
            val longRunningTest by creating {
                associateWith(main)
                associateWith(mainJdk7)
                associateWith(mainJdk8)
            }
            val recursiveDeletionTest by creating {
                associateWith(main)
                associateWith(mainJdk7)
                associateWith(mainJdk8)
            }
        }
    }
    js(IR) {
        if (!kotlinBuildProperties.isTeamcityBuild) {
            browser {}
        }
        nodejs {
            testTask {
                useMocha {
                    timeout = "10s"
                }
            }
        }

        compilerOptions {
            freeCompilerArgs.addAll(
                listOf(
                    "-Xallow-kotlin-package",
                    "-Xexpect-actual-classes",
                    "-Xklib-ir-inliner=intra-module",
                )
            )
        }

        compilations {
            val main by getting {
                compileTaskProvider.configure {
                    compilerOptions.mainCompilationOptions()
                    compilerOptions.freeCompilerArgs.addAll(
                        listOfNotNull(
                            "-Xir-module-name=$KOTLIN_JS_STDLIB_NAME",
                            diagnosticNamesArg,
                        )
                    )
                    compilerOptions.addReturnValueCheckerInfo()
                }
            }
        }
    }

    fun KotlinWasmTargetDsl.commonWasmTargetConfiguration() {
        (this as KotlinTargetWithNodeJsDsl).nodejs()
        (this as KotlinJsTargetDsl).compilerOptions {
            freeCompilerArgs.addAll(
                listOfNotNull(
                    "-Xallow-kotlin-package",
                    "-Xexpect-actual-classes",
                    "-Xklib-ir-inliner=intra-module",
                    "-source-map=false",
                    "-source-map-embed-sources=",
                    diagnosticNamesArg
                )
            )
        }
        compilations {
            val main by getting {
                compileTaskProvider.configure {
                    compilerOptions.mainCompilationOptions()
                    compilerOptions.addReturnValueCheckerInfo()
                    compilerOptions.freeCompilerArgs.add("-Xir-module-name=$KOTLIN_WASM_STDLIB_NAME")
                }
            }
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        commonWasmTargetConfiguration()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        commonWasmTargetConfiguration()
    }

    if (kotlinBuildProperties.isInIdeaSync) {
        val hostOs = System.getProperty("os.name")
        val isMingwX64 = hostOs.startsWith("Windows")
        val nativeTarget = when {
            hostOs == "Mac OS X" -> macosX64("native")
            hostOs == "Linux" -> linuxX64("native")
            isMingwX64 -> mingwX64("native")
            else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
        }
        nativeTarget.compilerOptions {
            freeCompilerArgs.addAll(
                listOf(
                    "-Xallow-kotlin-package",
                    "-Xexpect-actual-classes",
                    "-nostdlib",
                )
            )
        }
        nativeTarget.compilations["main"].compileTaskProvider.configure {
            compilerOptions.addReturnValueCheckerInfo()
        }
    }

sourceSets {
        fun <TP : TaskProvider<*>> TP.requiredForImport(): TP {
            tasks.findByName("prepareKotlinIdeaImport")?.dependsOn(this)
            return this
        }
        all {
            kotlin.setSrcDirs(emptyList<File>())
        }
        commonMain {
            val prepareCommonSources by tasks.registering {
                dependsOn(":prepare:build.version:writeStdlibVersion")
            }
            kotlin {
                srcDir("common/src")
                srcDir(files("src").builtBy(prepareCommonSources))
                srcDir("unsigned/src")
            }
        }
        commonTest {
            dependencies {
                api(kotlinTest())
            }
            kotlin {
                srcDir("common/test")
                srcDir("test")
            }
        }
        val jvmCompileOnlyDeclarations by getting {
            kotlin.srcDir("jvm/compileOnly")
        }
        val jvmMain by getting {
            project.configurations.getByName("jvmMainCompileOnly")
            dependencies {
                api("org.jetbrains:annotations:13.0")
            }
            val jvmSrcDirs = listOfNotNull(
                "jvm/src",
                "jvm/runtime",
                "jvm/builtins",
            )
            project.sourceSets["main"].java.srcDirs(*jvmSrcDirs.toTypedArray())
            kotlin.setSrcDirs(jvmSrcDirs)
            kotlin.exclude("kotlin/internal/InternalAnnotations.kt")
        }

        val jvmMainJdk7 by getting {
            kotlin.srcDir("jdk7/src")
        }
        val jvmMainJdk8 by getting {
            kotlin.srcDir("jdk8/src")
        }

        val jvmTest by getting {
            languageSettings {
                optIn("kotlin.io.path.ExperimentalPathApi")
            }
            dependencies {
                api(kotlinTest("junit"))
            }
            kotlin.srcDir("jvm/test")
            kotlin.srcDir("jdk7/test")
            kotlin.srcDir("jdk8/test")
        }

        val jvmLongRunningTest by getting {
            dependencies {
                api(kotlinTest("junit"))
            }
            kotlin.srcDir("jvm/testLongRunning")
        }

        val jvmRecursiveDeletionTest by getting {
            dependencies {
                api(kotlinTest("junit"))
            }
            kotlin.srcDir("jdk7/recursiveDeletionTest")
        }

        val commonNonJvmMain by creating {
            dependsOn(commonMain.get())
            kotlin.srcDir("common-non-jvm/src")
        }

        val webMain by creating {
            dependsOn(commonMain.get())
            kotlin {
                srcDir("common-js-wasmjs/src")
            }
        }

        val jsMain by getting {
            dependsOn(webMain)
            dependsOn(commonNonJvmMain)
            val prepareJsIrMainSources by tasks.registering(Sync::class)
            kotlin {
                srcDir(prepareJsIrMainSources.requiredForImport())
                srcDir("$jsDir/builtins")
                srcDir("$jsDir/runtime")
                srcDir("$jsDir/src").apply {
                    exclude("kotlin/browser")
                    exclude("kotlin/dom")
                    exclude("kotlinx")
                    exclude("org.w3c")
                }
            }

            prepareJsIrMainSources.configure {
                val ignoredFileNames = setOf("Atomics.kt", "AtomicArrays.kt")
                val unimplementedNativeBuiltIns =
                    (file(jvmBuiltinsDir).list()!!.toSortedSet() - file("$jsDir/builtins/").list()!!)
                        .filterNot { ignoredFileNames.contains(it) }
                        .map { "$jvmBuiltinsRelativeDir/$it" }

                val sources = unimplementedNativeBuiltIns

                sources.forEach { path ->
                    from("$rootDir/$path") {
                        into(path.dropLastWhile { it != '/' })
                    }
                }

                into(jsBuiltinsSrcDir)

                doLast {
                    unimplementedNativeBuiltIns.forEach { path ->
                        val file = File("$destinationDir/$path")
                        val sourceCode = file.readText()
                        file.writeText(sourceCode)
                    }
                }
            }
        }
        val jsTest by getting {
            kotlin.srcDir("${jsDir}/test")
        }

        val nativeWasmMain by creating {
            dependsOn(commonNonJvmMain)
            kotlin.srcDir("native-wasm/src")
        }

        val nativeWasmTest by creating {
            dependsOn(commonTest.get())
            kotlin.srcDir("native-wasm/test")
        }

    val wasmCommonMain by creating {
        dependsOn(nativeWasmMain)
        val prepareWasmBuiltinSources by tasks.registering(Sync::class)
        kotlin {
            srcDir(prepareWasmBuiltinSources.requiredForImport())
            srcDir("wasm/builtins")
            srcDir("wasm/internal")
            srcDir("wasm/runtime")
            srcDir("wasm/src")
            srcDir("wasm/stubs")
            // Component-only additions (no wasi imports)
            // component-only actuals are compiled in the separate 'component' compilation, not here
        }
            prepareWasmBuiltinSources.configure {
                val unimplementedNativeBuiltIns =
                    (file(jvmBuiltinsDir).list().toSortedSet() - file("wasm/builtins/kotlin/").list())
                        .map { "$jvmBuiltinsRelativeDir/$it" }

                val sources = unimplementedNativeBuiltIns

                val excluded = listOf(
                    "Atomics.kt", "AtomicArrays.kt",
                    // Included with K/N collections
                    "Collections.kt", "Iterator.kt"
                )

                sources.forEach { path ->
                    from("$rootDir/$path") {
                        into(path.dropLastWhile { it != '/' })
                        excluded.forEach {
                            exclude(it)
                        }
                    }
                }

                into(layout.buildDirectory.dir("src/wasm-builtin-sources"))
            }

        }
        val wasmCommonTest by creating {
            dependsOn(nativeWasmTest)
            kotlin {
                srcDir("wasm/test")
            }
        }

        val wasmJsMain by getting {
            dependsOn(webMain)
            dependsOn(wasmCommonMain)
            kotlin {
                srcDir("wasm/js/builtins")
                srcDir("wasm/js/internal")
                srcDir("wasm/js/src")
            }
        }
        val wasmJsTest by getting {
            dependsOn(wasmCommonTest)
            kotlin {
                srcDir("wasm/js/test")
            }
        }
        val wasmWasiMain by getting {
            dependsOn(wasmCommonMain)
            kotlin {
                srcDir("wasm/wasi/builtins")
                srcDir("wasm/wasi/src")
                exclude("unused/**")
            }
            languageSettings {
                optIn("kotlin.wasm.unsafe.UnsafeWasmMemoryApi")
            }
        }
        val wasmWasiTest by getting {
            dependsOn(wasmCommonTest)
            kotlin {
                srcDir("wasm/wasi/test")
            }
        }

        // Component-only source set: actuals without WASI imports
        val componentMain by creating {
            dependsOn(wasmCommonMain)
            kotlin {
                srcDir("wasm/component/src")
                // Safe builtins (Throwable, etc.)
                srcDir("wasm/wasi/builtins")
            }
            languageSettings { optIn("kotlin.wasm.unsafe.UnsafeWasmMemoryApi") }
        }

        if (kotlinBuildProperties.isInIdeaSync) {
            val nativeKotlinTestCommon by creating {
                dependsOn(commonMain.get())
                val prepareKotlinTestCommonNativeSources by tasks.registering(Sync::class) {
                    from("../kotlin.test/common/src/main/kotlin")
                    from("../kotlin.test/annotations-common/src/main/kotlin")
                    into(layout.buildDirectory.dir("src/native-kotlin-test-common-sources"))
                }

                kotlin {
                    srcDir(prepareKotlinTestCommonNativeSources.requiredForImport())
                }
            }
            val nativeMain by getting {
                dependsOn(nativeWasmMain)
                dependsOn(nativeKotlinTestCommon)
                kotlin {
                    srcDir("$rootDir/kotlin-native/runtime/src/main/kotlin")
                    srcDir("$rootDir/kotlin-native/Interop/Runtime/src/main/kotlin")
                    srcDir("$rootDir/kotlin-native/Interop/Runtime/src/native/kotlin")
                }
                languageSettings {
                    optIn("kotlin.native.internal.InternalForKotlinNative")
                }
            }
            val nativeTest by getting {
                dependsOn(nativeWasmTest)
                kotlin {
                    srcDir("$rootDir/kotlin-native/runtime/test")
                }
                languageSettings {
                    optIn("kotlin.experimental.ExperimentalNativeApi")
                    optIn("kotlin.native.ObsoleteNativeApi")
                    optIn("kotlin.native.runtime.NativeRuntimeApi")
                    optIn("kotlin.native.internal.InternalForKotlinNative")
                    optIn("kotlinx.cinterop.ExperimentalForeignApi")
                    optIn("kotlin.native.concurrent.ObsoleteWorkersApi")
                }
            }
        }

        all sourceSet@ {
            languageSettings {
                // TODO: progressiveMode = use build property 'test.progressive.mode'
                if (this@sourceSet == jvmCompileOnlyDeclarations) {
                    return@languageSettings
                }
                commonOptIns.forEach { optIn(it) }
                if (this@sourceSet.name.endsWith("Test")) {
                    commonTestOptIns.forEach { optIn(it) }
                }
            }
        }
    }
}

// Create an additional 'component' compilation under wasmWasi target that reuses wasmCommonMain sources
afterEvaluate {
    val wasmWasiTarget = kotlin.targets
        .withType<org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget>()
        .firstOrNull { it.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.wasm && it.wasmTargetType?.name == "WASI" }
        ?: return@afterEvaluate

    // Create a separate compilation 'component' that compiles only the shared wasm sources (no wasi)
    val componentCompilation = wasmWasiTarget.compilations.findByName("component")
        ?: wasmWasiTarget.compilations.create("component")

    val componentMain = kotlin.sourceSets.getByName("componentMain")
    // Wire component compilation to componentMain (actuals without WASI imports)
    componentCompilation.defaultSourceSet.dependsOn(componentMain)

    // Ensure UnsafeWasmMemoryApi is opted-in for component sources
    kotlin.sourceSets.findByName("wasmWasiComponent")?.languageSettings?.apply {
        optIn("kotlin.wasm.unsafe.UnsafeWasmMemoryApi")
    }

    // Align compiler options with main wasmWasi compilation
    componentCompilation.compileTaskProvider.configure {
        compilerOptions.mainCompilationOptions()
        compilerOptions.addReturnValueCheckerInfo()
        compilerOptions.freeCompilerArgs.add("-Xir-module-name=$KOTLIN_WASM_STDLIB_NAME")
    }
}

dependencies {
    val jvmMainApi by configurations.getting
    val metadataApiElements by configurations.getting
    val nativeApiElements = configurations.maybeCreate("nativeApiElements")
    constraints {
        // there is no dependency anymore from kotlin-stdlib to kotlin-stdlib-common,
        // but use this constraint to align it if another library brings it transitively
        jvmMainApi(project(":kotlin-stdlib-common"))
        metadataApiElements(project(":kotlin-stdlib-common"))
        nativeApiElements(project(":kotlin-stdlib-common"))
        // to avoid split package and duplicate classes on classpath after moving them from these artifacts in 1.8.0
        jvmMainApi("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.0")
        jvmMainApi("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.0")
    }
}

tasks {
    val allMetadataJar by existing(Jar::class) {
        archiveClassifier = "all"
    }
    val metadataJar by registering(Jar::class) {
        archiveAppendix.set("metadata")
        archiveExtension.set("klib")
    }
    kotlin.metadata().compilations.named { it == "commonMain" }.configureEach {
        metadataJar.configure { from(output.allOutputs) }
    }
    val sourcesJar by existing(Jar::class) {
        archiveAppendix.set("metadata")
    }
    val jvmJar by existing(Jar::class) {
        duplicatesStrategy = DuplicatesStrategy.FAIL
        archiveAppendix.set(null as String?)
        manifestAttributes(manifest, "Main", multiRelease = true)
        manifest.attributes(mapOf("Implementation-Title" to "kotlin-stdlib"))
        from(kotlin.jvm().compilations["mainJdk7"].output.allOutputs)
        from(kotlin.jvm().compilations["mainJdk8"].output.allOutputs)
        from(project.sourceSets["java9"].output)
    }

    val jvmRearrangedSourcesJar by registering(Jar::class) {
        archiveClassifier.set("jvm-sources")
        archiveVersion.set("")
        destinationDirectory.set(layout.buildDirectory.dir("lib"))

        includeEmptyDirs = false
        duplicatesStrategy = DuplicatesStrategy.FAIL

        into("commonMain") {
            from(kotlin.sourceSets.commonMain.get().kotlin)
        }
        into("jvmMain") {
            from(kotlin.sourceSets["jvmMain"].kotlin) {
                // relocate builtins sources that get placed in the root of the sources file tree
                eachFile {
                    val sourcePathSegments = relativeSourcePath.segments
                    if (sourcePathSegments.size == 1) {
                        relativePath = RelativePath(true, "jvmMain", "kotlin", *sourcePathSegments)
                    }
                }
            }
            from(kotlin.sourceSets["jvmMainJdk7"].kotlin) {
                into("jdk7")
            }
            from(kotlin.sourceSets["jvmMainJdk8"].kotlin) {
                into("jdk8")
            }
        }
    }

    val jvmSourcesJar by existing(Jar::class) {
        duplicatesStrategy = DuplicatesStrategy.FAIL
        archiveAppendix.set(null as String?)

        val jvmSourcesJarFile = jvmRearrangedSourcesJar.get().archiveFile
        inputs.file(jvmSourcesJarFile)
        doLast {
            jvmSourcesJarFile.get().asFile.toPath().copyTo(archiveFile.get().asFile.toPath(), overwrite = true)
        }
    }

    dexMethodCount {
        from(jvmJar)
        ownPackages.set(listOf("kotlin"))
    }

    val jsJar by existing(Jar::class) {
        manifestAttributes(manifest, "Main")
        manifest.attributes(mapOf("Implementation-Title" to "kotlin-stdlib-js"))
    }

    val jsJarForTests by registering(Copy::class) {
        from(jsJar)
        rename { _ -> "full-runtime.klib" }
        // some tests expect stdlib-js klib in this location
        into(rootProject.layout.buildDirectory.dir("js-ir-runtime"))
    }

    val jsRearrangedSourcesJar by registering(Jar::class) {
        archiveClassifier.set("js-sources")
        archiveVersion.set("")
        destinationDirectory.set(layout.buildDirectory.dir("lib"))

        includeEmptyDirs = false
        duplicatesStrategy = DuplicatesStrategy.FAIL

        into("commonMain") {
            from(kotlin.sourceSets.commonMain.get().kotlin)
        }
        into("jsMain") {
            from(kotlin.sourceSets["jsMain"].kotlin) {
                // just to depend on source-generating tasks
                exclude("**")
            }
            from(jvmBuiltinsDir) {
                into("kotlin")
                include("Comparable.kt")
                include("Enum.kt")
            }
            from("$jsBuiltinsSrcDir/libraries/stdlib/jvm") {
                exclude("builtins/Comparable.kt")
            }
            from("$jsBuiltinsSrcDir/libraries/stdlib/js/src")
            from("$jsDir/builtins") {
                into("kotlin")
                exclude("Enum.kt")
            }
            from("$jsDir/runtime") {
                into("runtime")
            }
            from("$jsDir/src") {
                include("**/*.kt")
            }
        }
    }

    val jsSourcesJar by existing(Jar::class) {
        val jsSourcesJarFile = jsRearrangedSourcesJar.get().archiveFile
        inputs.file(jsSourcesJarFile)
        doLast {
            jsSourcesJarFile.get().asFile.toPath().copyTo(archiveFile.get().asFile.toPath(), overwrite = true)
        }
    }

    val wasmJsJar by existing(Jar::class) {
        manifestAttributes(manifest, "Main")
        manifest.attributes(mapOf("Implementation-Title" to "kotlin-stdlib-wasm-js"))
    }
    // Canonical WASI (preview2/component model) KLIB
    val wasmWasiJar by registering(Jar::class) {
        archiveExtension.set("klib")
        // Use the same base name as publication artifactId (set below)
        archiveBaseName.set("${base.archivesName.get()}-wasm-wasi")
        duplicatesStrategy = DuplicatesStrategy.FAIL
        manifestAttributes(manifest, "Main")
        manifest.attributes(mapOf("Implementation-Title" to "kotlin-stdlib-wasm-wasi"))

        // Pack outputs of the custom 'component' compilation under the wasmWasi target (resolve lazily)
        val componentOutputs = providers.provider {
            val comp = kotlin.targets.getByName("wasmWasi").compilations.getByName("component")
            comp.output.allOutputs
        }
        from(componentOutputs)
        // Ensure the component compilation runs before packaging
        dependsOn(providers.provider {
            val comp = kotlin.targets.getByName("wasmWasi").compilations.getByName("component")
            comp.compileTaskProvider
        })
    }

    artifacts {
        val distJsJar = configurations.create("distJsJar")
        val distJsSourcesJar = configurations.create("distJsSourcesJar")
        val distJsKlib = configurations.create("distJsKlib")
        val commonMainMetadataElements by configurations.creating

        add(distJsSourcesJar.name, jsSourcesJar)
        add(distJsKlib.name, jsJar)
        add(commonMainMetadataElements.name, metadataJar)
    }


    val jvmTest by existing(Test::class)

    listOf(JdkMajorVersion.JDK_9_0, JdkMajorVersion.JDK_11_0).forEach { jvmVersion ->
        val jvmVersionTest = register("jvm${jvmVersion.majorVersion}Test", Test::class) {
            group = "verification"
            javaLauncher.set(getToolchainLauncherFor(jvmVersion))
            // additional test tasks are not configured automatically same as the main test task
            // after KMP plugin stopped applying java plugin
            classpath = jvmTest.get().classpath
            testClassesDirs = jvmTest.get().testClassesDirs

        }
        check.configure { dependsOn(jvmVersionTest) }
    }

    val jvmLongRunningTest by registering(Test::class) {
        group = "verification"
        val compilation = kotlin.jvm().compilations["longRunningTest"]
        classpath = compilation.compileDependencyFiles + compilation.runtimeDependencyFiles + compilation.output.allOutputs
        testClassesDirs = compilation.output.classesDirs
    }

    if (project.hasProperty("kotlin.stdlib.test.long.running")) {
        check.configure { dependsOn(jvmLongRunningTest) }
    }

    listOf("Js", "Wasi").forEach { wasmTarget ->
        named("compileTestKotlinWasm$wasmTarget", AbstractKotlinCompile::class) {
            // TODO: fix all warnings, enable -Werror
            compilerOptions.suppressWarnings = true
            // exclusions due to KT-51647
            exclude("generated/minmax/*")
            exclude("collections/MapTest.kt")
        }
        named("compileTestDevelopmentExecutableKotlinWasm$wasmTarget", KotlinJsIrLink::class) {
            compilerOptions.freeCompilerArgs.add("-Xwasm-enable-array-range-checks")
        }
        named("compileTestProductionExecutableKotlinWasm$wasmTarget", KotlinJsIrLink::class) {
            enabled = false  // Causes out-of-memory in CI: KTI-2150
        }
    }
    val wasmWasiNodeTest by existing {
        if (!kotlinBuildProperties.getBoolean("kotlin.stdlib.wasi.tests")) {
            enabled = false
        }
    }

    /*
    We are using a custom 'kotlin-project-structure-metadata' to ensure 'nativeApiElements' lists 'commonMain' as source set
    */
    val generateProjectStructureMetadata by existing(GenerateProjectStructureMetadata::class) {
        val outputTestFile = file("kotlin-project-structure-metadata.beforePatch.json")
        val patchedFile = file("kotlin-project-structure-metadata.json")

        inputs.file(patchedFile)
        inputs.file(outputTestFile)
        inputs.property("isInIdeaSync", kotlinBuildProperties.isInIdeaSync)

        // overwrite kotlin-project-structure-metadata when building the artifact,
        // but use automatically generated one when importing the project
        // because of the different source set structure
        if (!kotlinBuildProperties.isInIdeaSync) {
            doLast {
                // Copy patched metadata without strict equality enforcement for local custom variants
                patchedFile.copyTo(resultFile, overwrite = true)
            }
        }
    }

    val jvmRecursiveDeletionTestTmpDir = layout.buildDirectory.asFile.map {
        it.toPath().resolve("recursiveDeletionTestsWorkDir")
    }

    val jvmRecursiveDeletionTestCleanup by registering(Delete::class) {
        setDelete(jvmRecursiveDeletionTestTmpDir)
    }

    // A dedicated task for tests on files and directories deletion from the current working directory.
    // To prevent (to some extent) accidental removal of surrounding files and directories when tested functions
    // are malfunctioning, this task gets its own working directory where removal will take place.
    val jvmRecursiveDeletionTest by registering(Test::class) {
        group = "verification"
        val compilation = kotlin.jvm().compilations["recursiveDeletionTest"]

        testClassesDirs = compilation.output.classesDirs
        classpath = compilation.compileDependencyFiles + compilation.runtimeDependencyFiles + compilation.output.allOutputs

        doFirst {
            workingDir = jvmRecursiveDeletionTestTmpDir.get().toFile()
            workingDir.deleteRecursively()
            workingDir.mkdirs()
        }
        finalizedBy(jvmRecursiveDeletionTestCleanup)
    }
    check.configure { dependsOn(jvmRecursiveDeletionTest) }
}


// region ==== Publishing ====

configureDefaultPublishing()


val emptyJavadocJar by tasks.creating(org.gradle.api.tasks.bundling.Jar::class) {
    archiveClassifier.set("javadoc")
}

publishing {
    val artifactBaseName = base.archivesName.get()
    configureMultiModuleMavenPublishing {
        val rootModule = module("rootModule") {
            mavenPublication {
                artifactId = artifactBaseName
                configureKotlinPomAttributes(project, "Kotlin Standard Library")
                artifact(emptyJavadocJar)
            }

            // creates a variant from existing configuration or creates new one
            variant("jvmApiElements")
            variant("jvmRuntimeElements")
            variant("jvmSourcesElements")

            variant("metadataApiElements")
            variant("metadataSourcesElementsFromJvm") {
                name = "metadataSourcesElements"
                configuration {
                    // to avoid clash in Gradle 8+ with metadataSourcesElements configuration with the same attributes
                    isCanBeConsumed = false
                }
                attributes {
                    copyAttributes(from = project.configurations["metadataSourcesElements"].attributes, to = this)
                }
                artifact(tasks["sourcesJar"]) {
                    classifier = "common-sources"
                }
            }
            variant("nativeApiElements") {
                attributes {
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
                    attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named("non-jvm"))
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(KotlinUsages.KOTLIN_API))
                    attribute(KotlinPlatformType.attribute, KotlinPlatformType.native)
                }
            }
        }

        val js = module("jsModule") {
            mavenPublication {
                artifactId = "$artifactBaseName-js"
                configureKotlinPomAttributes(project, "Kotlin Standard Library for JS", packaging = "klib")
            }
            variant("jsApiElements")
            variant("jsRuntimeElements")
            variant("jsSourcesElements")
        }

        val wasmJs = module("wasmJsModule") {
            mavenPublication {
                artifactId = "$artifactBaseName-wasm-js"
                configureKotlinPomAttributes(project, "Kotlin Standard Library for experimental WebAssembly JS platform", packaging = "klib")
            }
            variant("wasmJsApiElements")
            variant("wasmJsRuntimeElements")
            variant("wasmJsSourcesElements")
        }
        val wasmWasi = module("wasmWasiModule") {
            mavenPublication {
                artifactId = "$artifactBaseName-wasm-wasi"
                configureKotlinPomAttributes(
                    project,
                    "Kotlin Standard Library for experimental WebAssembly WASI (preview2 component model)",
                    packaging = "klib",
                )
            }
            val wasmPreviewAttr = Attribute.of("org.jetbrains.kotlin.wasm.preview", String::class.java)
            val wasmImportsAttr = Attribute.of("org.jetbrains.kotlin.wasm.imports", String::class.java)
            val wasmTargetAttr = KotlinWasmTargetAttribute.wasmTargetAttribute
            val klibPackagingAttr = Attribute.of("org.jetbrains.kotlin.klib.packaging", String::class.java)

            variant("wasmWasiComponentApiElements") {
                attributes {
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(KotlinUsages.KOTLIN_API))
                    attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named("non-jvm"))
                    attribute(KotlinPlatformType.attribute, KotlinPlatformType.wasm)
                    attribute(klibPackagingAttr, "packed")
                    attribute(wasmImportsAttr, "preview2")
                    attribute(wasmPreviewAttr, "preview2")
                    attribute(wasmTargetAttr, KotlinWasmTargetAttribute.wasi)
                }
                artifact(tasks["wasmWasiJar"]) {
                    builtBy(tasks["wasmWasiJar"])
                }
            }
            variant("wasmWasiComponentRuntimeElements") {
                attributes {
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(KotlinUsages.KOTLIN_RUNTIME))
                    attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named("non-jvm"))
                    attribute(KotlinPlatformType.attribute, KotlinPlatformType.wasm)
                    attribute(klibPackagingAttr, "packed" )
                    attribute(wasmImportsAttr, "preview2")
                    attribute(wasmPreviewAttr, "preview2")
                    attribute(wasmTargetAttr, KotlinWasmTargetAttribute.wasi)
                }
                artifact(tasks["wasmWasiJar"]) {
                    builtBy(tasks["wasmWasiJar"])
                }
            }
            variant("wasmWasiComponentSourcesElements") {
                attributes {
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(KotlinUsages.KOTLIN_RUNTIME))
                    attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES))
                    attribute(wasmImportsAttr, "preview2")
                    attribute(wasmPreviewAttr, "preview2")
                    attribute(wasmTargetAttr, KotlinWasmTargetAttribute.wasi)
                }
            }
        }

        // Makes all variants from accompanying artifacts visible through `available-at`
        rootModule.include(js, wasmJs, wasmWasi)
    }

    publications {
        val rootModule by existing(MavenPublication::class)
        val jsModule by existing(MavenPublication::class)
        configureSbom("Main", "kotlin-stdlib", setOf("jvmRuntimeClasspath"), rootModule)
        configureSbom("Js", "kotlin-stdlib-js", setOf("jsRuntimeClasspath"), jsModule)

        val wasmJsModule by existing(MavenPublication::class)
        val wasmWasiModule by existing(MavenPublication::class)
        configureSbom("Wasm-Js", "kotlin-stdlib-wasm-js", setOf("wasmJsRuntimeClasspath"), wasmJsModule)
        configureSbom("Wasm-Wasi", "kotlin-stdlib-wasm-wasi", setOf("wasmWasiComponentRuntimeClasspath"), wasmWasiModule)
    }
}

private val wasmImportsAttribute = Attribute.of("org.jetbrains.kotlin.wasm.imports", String::class.java)
private val wasmPreviewAttribute = Attribute.of("org.jetbrains.kotlin.wasm.preview", String::class.java)

kotlin.targets.withType<KotlinJsIrTarget>().configureEach {
    if (platformType == KotlinPlatformType.wasm && wasmTargetType == KotlinWasmTargetType.WASI) {
        listOf("${targetName}ApiElements", "${targetName}RuntimeElements").forEach { configurationName ->
            configurations.matching { it.name == configurationName }.configureEach {
                attributes.attribute(wasmImportsAttribute, "preview2")
                attributes.attribute(wasmPreviewAttribute, "preview2")
            }
        }
    }
}


// endregion

// for legacy intra-project dependencies
for (name in listOf("sources", "distSources")) {
    val sourcesConfiguration = configurations.getOrCreate(name).apply {
        isCanBeResolved = false
        isCanBeConsumed = true
    }
    artifacts.add(sourcesConfiguration.name, tasks["jvmSourcesJar"])
}

// Disabling IC for JS tasks as they may produce false-positive compilation failure
tasks.withType<Kotlin2JsCompile>().configureEach {
    incremental = false
}

// --- WASI Preview 2 bindings sync ---

val wasiPreview2Repo = "https://github.com/WebAssembly/WASI"
val wasiPreview2Tag = "v0.2.8"
val wasiPreview2Archive = layout.buildDirectory.file("wit-sources/wasi-preview2.zip")
val wasiPreview2ExtractDir = layout.buildDirectory.dir("wit-sources/wasi-preview2")
val wasiPreview2UpstreamDir = layout.projectDirectory.dir("wasm/wasi/wit-upstream")
val wasiPreview2ArchiveRoot = "WASI-${wasiPreview2Tag.removePrefix("v")}"

val downloadWasiPreview2 by tasks.registering(Download::class) {
    src("$wasiPreview2Repo/archive/refs/tags/$wasiPreview2Tag.zip")
    dest(wasiPreview2Archive)
    onlyIfModified(true)
}

val unpackWasiPreview2 by tasks.registering(Copy::class) {
    dependsOn(downloadWasiPreview2)
    from(provider { zipTree(wasiPreview2Archive.get().asFile) })
    into(wasiPreview2ExtractDir)
}

val syncWasiPreview2 by tasks.registering(Sync::class) {
    dependsOn(unpackWasiPreview2)
    from(wasiPreview2ExtractDir.map { extracted ->
        extracted.dir("$wasiPreview2ArchiveRoot/wasip2")
    })
    into(wasiPreview2UpstreamDir)
}

val wasiPreview2SchemaPackages = listOf("io", "clocks", "filesystem", "random", "sockets", "cli", "http")
val wasiPreview2ModuleName = "kotlin-wasm-wasi-preview2"
val wasiPreview2KlibOutput = layout.buildDirectory.dir("wit-klibs/wasi-preview2")

val generateWasiPreview2Klib by tasks.registering(WitCodegenTask::class) {
    notCompatibleWithConfigurationCache("Uses project APIs during task action; will be made CC-friendly.")
    dependsOn(syncWasiPreview2)
    // Ensure local runtime .klib is built and available
    dependsOn(":wit:runtime:syncWasmRuntimeKlib")
    // Ensure the freshly built WIT compiler plugin jar is available and wire it into the task
    dependsOn(":wit:compiler-plugin:jar")
    moduleName.set(wasiPreview2ModuleName)
    outputDirectory.set(wasiPreview2KlibOutput)
    schemaRoots.from(wasiPreview2SchemaPackages.map { pkg -> wasiPreview2UpstreamDir.dir(pkg) })
    features.set(listOf("resources"))
    debug.set(true)
    // Provide wasm stdlib and transitive runtime klibs for isolated offline compilation
    val kotlinVersion = project.version.toString()
    val m2 = providers.systemProperty("user.home").map { home ->
        layout.projectDirectory.file("$home/.m2/repository/org/jetbrains/kotlin/kotlin-stdlib-wasm-wasi/$kotlinVersion/kotlin-stdlib-wasm-wasi-$kotlinVersion.klib").asFile
    }
    val atomicfu = layout.projectDirectory.file("dist/maven/org/jetbrains/kotlin/kotlinx-atomicfu-runtime/$kotlinVersion/kotlinx-atomicfu-runtime-$kotlinVersion.klib").asFile
    // Plugin jar: take the jar built by :wit:compiler-plugin
    val witPluginJar = project(":wit:compiler-plugin").tasks.named<org.gradle.jvm.tasks.Jar>("jar").flatMap { it.archiveFile }
    pluginJar.set(witPluginJar)

    // Include stdlib klibs and any locally compiled runtime klib(s) if present
    libraries.from(files(m2, atomicfu).filter { it.exists() })
    // Include the locally built runtime .klib (mandatory for IR glue)
    val witRuntimeKlib = project(":wit:runtime").layout.buildDirectory.file("klib/kotlin-wit-runtime.klib")
    libraries.from(witRuntimeKlib.map { it.asFile })
}

val wasiPreview2KlibFile = generateWasiPreview2Klib.flatMap { task ->
    task.outputDirectory.file("$wasiPreview2ModuleName.klib")
}

tasks.withType<AbstractKotlinCompile<*>>()
    .matching { it.name.contains("WasmWasi", ignoreCase = true) }
    .configureEach {
        dependsOn(generateWasiPreview2Klib)
    }

kotlin.sourceSets.named("wasmWasiMain") {
    dependencies {
        implementation(files(wasiPreview2KlibFile))
    }
}

kotlin.sourceSets.named("componentMain") {
    dependencies {
        implementation(files(wasiPreview2KlibFile))
    }
}
