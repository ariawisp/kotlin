plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "Kotlin WIT codegen core (schema + AST loader)"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // The WIT parser is brought in via includeBuild in the root settings.
    implementation("com.ariawisp.wit:kotlin-wit-parser:0.1-SNAPSHOT")
}

tasks.test {
    useJUnitPlatform()
}

