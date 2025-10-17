description = "Kotlin WIT Compiler Plugin"

plugins {
    kotlin("jvm")
    id("jps-compatible")
    id("project-tests-convention")
}

dependencies {
    implementation("com.ariawisp.wit:kotlin-wit-parser:0.1-SNAPSHOT")
    implementation(project(":wit:codegen-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    compileOnly(project(":wit:runtime"))

    compileOnly(project(":compiler:plugin-api"))
    compileOnly(project(":compiler:fir:entrypoint"))
    compileOnly(project(":compiler:fir:resolve"))
    compileOnly(project(":compiler:fir:plugin-utils"))
    compileOnly(project(":compiler:ir.backend.common"))
    compileOnly(intellijCore())

    runtimeOnly(kotlinStdlib())
    runtimeOnly(project(":compiler:fir:plugin-utils"))

    testImplementation(kotlin("test-junit5"))
    testImplementation(intellijCore())
    testImplementation(project(":wit:runtime"))
    testImplementation(testFixtures(project(":compiler:test-infrastructure")))
    testImplementation(testFixtures(project(":compiler:test-infrastructure-utils")))
    testImplementation(testFixtures(project(":compiler:tests-common-new")))
    testImplementation(testFixtures(project(":compiler:tests-common")))
    testImplementation(project(":compiler:cli"))
    testImplementation(project(":compiler:backend.common.jvm"))
    testImplementation(project(":compiler:backend.jvm"))
    testImplementation(project(":compiler:ir.backend.common"))
    testImplementation(project(":compiler:ir.tree"))
    testImplementation(project(":core:util.runtime"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

optInToExperimentalCompilerApi()

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

runtimeJar()
sourcesJar()
javadocJar()

projectTests {
    testTask(jUnitMode = JUnitMode.JUnit5) {
        workingDir = rootDir
    }
}
