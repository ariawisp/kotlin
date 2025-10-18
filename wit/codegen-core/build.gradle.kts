plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "Kotlin WIT codegen core (schema + AST loader)"

repositories {
    mavenCentral()
}

kotlin {
    // Ensure test runtime matches parser build (JDK 21 classfile target)
    jvmToolchain(21)
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // The WIT parser is brought in via includeBuild in the root settings.
    implementation("com.ariawisp.wit:kotlin-wit-parser:0.1-SNAPSHOT")

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
    // Run tests on JDK 21 to load parser classes compiled for 65.0
    val toolchains = project.extensions.getByType(org.gradle.jvm.toolchain.JavaToolchainService::class.java)
    javaLauncher.set(toolchains.launcherFor { languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(21)) })
}
