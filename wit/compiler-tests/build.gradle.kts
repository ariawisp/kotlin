import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

description = "Tests for the Kotlin WIT compiler plugin"

plugins {
    kotlin("jvm")
    id("jps-compatible")
    id("project-tests-convention")
}

dependencies {
    testImplementation(project(":wit:compiler-plugin"))
    testImplementation(kotlin("test"))

    testImplementation(testFixtures(project(":compiler:test-infrastructure")))
    testImplementation(testFixtures(project(":compiler:test-infrastructure-utils")))
    testImplementation(testFixtures(project(":compiler:tests-common-new")))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

sourceSets {
    "main" { none() }
    "test" { projectDefault() }
}

projectTests {
    withJvmStdlibAndReflect()

    testTask(jUnitMode = JUnitMode.JUnit5) {
        workingDir = rootDir
    }
}

val compilerPluginCompileKotlin by lazy {
    project(":wit:compiler-plugin").tasks.withType<KotlinCompile>().named("compileKotlin")
}

tasks.withType<KotlinCompile>().named("compileTestKotlin") {
    dependsOn(compilerPluginCompileKotlin)
    compilerOptions.freeCompilerArgs.add(
        "-Xfriend-paths=${compilerPluginCompileKotlin.get().destinationDirectory.get().asFile.absolutePath}",
    )
}
