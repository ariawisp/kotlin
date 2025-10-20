import org.gradle.api.tasks.JavaExec
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
    testImplementation(kotlin("test"))
    testImplementation("com.ariawisp.wit:kotlin-wit-parser:0.1-SNAPSHOT")
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
    val runtimeClasspath = configurations.named("runtimeClasspath")
    classpath = runtimeClasspath.get()
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
