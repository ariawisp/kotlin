import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Optional
import org.gradle.kotlin.dsl.register

plugins {
    kotlin("jvm")
}

description = "Preview-2 E2E harness (JVM) for WIT compiler plugin"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":kotlinx-metadata-klib"))
    implementation("com.ariawisp.wit:kotlin-wit-parser:0.1-SNAPSHOT")
    testImplementation(kotlin("test"))
    testImplementation(project(":kotlinx-metadata-klib"))
}

kotlin {
    jvmToolchain(17)
}


tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("dumpPreview2Metadata") {
    group = "verification"
    description = "Writes the Preview-2 binding metadata snapshot to build/preview2/preview2-metadata.json"
    dependsOn(":kotlin-stdlib:generateWasiPreview2Klib", "classes")
    val runtimeClasspath = configurations.named("runtimeClasspath").get()
    val mainClassesDir = layout.buildDirectory.dir("classes/kotlin/main")
    classpath = runtimeClasspath + files(mainClassesDir)
    val outputFileProvider = layout.buildDirectory.file("preview2/preview2-metadata.json")
    outputs.file(outputFileProvider)
    val outputFile = outputFileProvider.get().asFile
    args(outputFile.absolutePath)
    doFirst {
        outputFile.parentFile.mkdirs()
    }
    systemProperty("kotlin.repo.root", project.rootDir.absolutePath)
    mainClass.set("org.jetbrains.kotlin.wit.e2e.Preview2MetadataDump")
}

// Stage 2 – Symbol snapshot and verification tasks
val preview2DumpFile = layout.buildDirectory.file("preview2/preview2-metadata.json")

tasks.register<Sync>("generatePreview2SymbolSnapshot") {
    group = "verification"
    description = "Copies the Preview-2 metadata dump into docs/wasm/snapshots for CI drift checks"
    dependsOn("dumpPreview2Metadata")
    from(preview2DumpFile)
    into(rootProject.layout.projectDirectory.dir("docs/wasm/snapshots"))
    rename { _ -> "preview2-metadata.json" }
}

abstract class VerifyPreview2SymbolSnapshot : DefaultTask() {
    @get:InputFile
    abstract val dumpFile: RegularFileProperty

    @get:InputFile
    @get:Optional
    abstract val snapshotFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val dump = dumpFile.get().asFile
        val snapshot = snapshotFile.orNull?.asFile
        if (snapshot == null || !snapshot.isFile) {
            throw GradleException("Preview-2 symbol snapshot is missing. Run :wit:e2e-harness-jvm:generatePreview2SymbolSnapshot and commit docs/wasm/snapshots/preview2-metadata.json")
        }
        val dumpText = dump.readText().trim()
        val snapText = snapshot.readText().trim()
        if (dumpText != snapText) {
            throw GradleException("Preview-2 symbol snapshot drift detected. Regenerate with :wit:e2e-harness-jvm:generatePreview2SymbolSnapshot and commit the update.")
        }
    }
}

tasks.register<VerifyPreview2SymbolSnapshot>("verifyPreview2SymbolSnapshot") {
    group = "verification"
    description = "Fails if the Preview-2 metadata dump differs from the committed snapshot"
    dependsOn("dumpPreview2Metadata")
    dumpFile.set(layout.buildDirectory.file("preview2/preview2-metadata.json"))
    snapshotFile.set(rootProject.layout.projectDirectory.file("docs/wasm/snapshots/preview2-metadata.json"))
}
