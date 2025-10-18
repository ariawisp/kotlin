description = "Kotlin WIT codegen driver (shared IR pipeline)"

plugins {
    kotlin("jvm")
    id("jps-compatible")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":wit:codegen-core"))
    implementation("com.ariawisp.wit:kotlin-wit-parser:0.1-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    compileOnly(project(":wit:runtime"))

    compileOnly(project(":compiler:plugin-api"))
    compileOnly(project(":compiler:fir:entrypoint"))
    compileOnly(project(":compiler:fir:resolve"))
    compileOnly(project(":compiler:fir:plugin-utils"))
    compileOnly(project(":compiler:ir.backend.common"))
    compileOnly(project(":compiler:ir.tree"))
    compileOnly(intellijCore())

    runtimeOnly(kotlinStdlib())
    runtimeOnly(project(":compiler:fir:plugin-utils"))

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))
    testImplementation(project(":compiler:cli-common"))
}

optInToExperimentalCompilerApi()

sourceSets {
    "main" {
        java.setSrcDirs(listOf("src/main/kotlin"))
        resources.setSrcDirs(listOf("src/main/resources"))
    }
    "test" {
        java.setSrcDirs(listOf("src/test/kotlin"))
        resources.setSrcDirs(listOf("src/test/resources"))
    }
}

runtimeJar()
sourcesJar()
javadocJar()

tasks.test {
    useJUnitPlatform()
    workingDir = rootDir
}
