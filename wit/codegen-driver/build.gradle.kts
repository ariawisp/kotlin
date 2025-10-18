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

    // Avoid project dependency cycles by compiling against the shaded compiler embeddable jar
    val compilerEmbeddableJar: File? = listOf(
        rootDir.resolve("build/repo/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.3.0-wit.1/kotlin-compiler-embeddable-2.3.0-wit.1.jar"),
        rootDir.resolve("dist/kotlinc/lib/kotlin-compiler.jar"),
    ).firstOrNull { it.exists() }
    compilerEmbeddableJar?.let { compileOnly(files(it)) }

    runtimeOnly(kotlinStdlib())
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))
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
