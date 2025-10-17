plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.wit.gradle")
}

description = "Preview-2 E2E harness (JVM) for WIT compiler plugin"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":wit:runtime"))
    testImplementation(kotlin("test"))
    // Subplugin wires compiler plugin classpath entries.
}

kotlin {
    jvmToolchain(17)
}

extensions.configure(org.jetbrains.kotlin.wit.gradle.WitGradleSubplugin.WitExtension::class.java) {
    debug.set(true)
    root("src/test/wit/root.wit")
}

tasks.test {
    useJUnitPlatform()
}
