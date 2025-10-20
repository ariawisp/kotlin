description = "Kotlin WIT Compiler Plugin"

plugins {
    kotlin("jvm")
    id("jps-compatible")
    id("project-tests-convention")
}

kotlin {
    jvmToolchain(21)
}

// Allow fully skipping WIT compiler plugin tasks for bootstrap seeding on Space snapshots
val witSkipBuild = providers.gradleProperty("wit.skipBuild").map { it.toBoolean() }.orElse(false)
tasks.configureEach {
    onlyIf { !witSkipBuild.get() }
}

// Configuration used by runtimeJar() via addEmbeddedRuntime() to pack runtime deps into the -Xplugin jar
val embedded = configurations.findByName("embedded") ?: configurations.create("embedded")
configurations.named(embedded.name) {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
}

dependencies {
    implementation(project(":wit:codegen-driver"))
    implementation("com.ariawisp.wit:kotlin-wit-parser:0.1-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // Avoid compile-time dependency on runtime; driver resolves runtime symbols by FQNs at IR time

    // Compile against the shaded compiler embeddable to avoid project dependency cycles.
    // Space bootstrap supplies kotlin-compiler-embeddable; rely on bootstrap version rather than dist fallbacks.
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:${project.bootstrapKotlinVersion}")

    // Use bootstrap stdlib to avoid project dependency cycles when tasks in :kotlin-stdlib depend on this jar
    runtimeOnly(kotlin("stdlib", project.bootstrapKotlinVersion))

    // Embed plugin runtime deps into -Xplugin jar
    embedded(project(":wit:codegen-core")) { isTransitive = false }
    embedded(project(":wit:codegen-driver")) { isTransitive = false }
    embedded("com.ariawisp.wit:kotlin-wit-parser:0.1-SNAPSHOT") { isTransitive = false }
    embedded("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") { exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib") }

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

// Allow lighter local rebuilds to avoid pulling test infrastructure
val witFastRebuild = providers.gradleProperty("wit.fastRebuild").orNull == "true"

runtimeJar()
sourcesJar()
javadocJar()

if (!witFastRebuild) {
    projectTests {
        testTask(jUnitMode = JUnitMode.JUnit5) {
            workingDir = rootDir
        }
    }
}
