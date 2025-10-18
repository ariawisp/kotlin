plugins {
    `java-gradle-plugin`
}

description = "Gradle wrapper for the Kotlin WIT compiler plugin"

val kotlinRootDir = rootDir.parentFile?.parentFile
    ?: error("Unable to locate Kotlin root directory from $rootDir")

fun locateJar(relativeDir: String, predicate: (File) -> Boolean, buildTask: String): File {
    val dir = kotlinRootDir.resolve(relativeDir)
    check(dir.exists()) {
        "Directory '$dir' not found. Run './gradlew $buildTask' before configuring included build $name."
    }
    return dir.listFiles { file ->
        file.isFile && predicate(file)
    }?.firstOrNull() ?: error("Expected jar in '$dir'. Run './gradlew $buildTask' before configuring included build $name.")
}

fun locateJarOrNull(relativeDir: String, predicate: (File) -> Boolean): File? {
    val dir = kotlinRootDir.resolve(relativeDir)
    if (!dir.exists()) return null
    return dir.listFiles { file ->
        file.isFile && predicate(file)
    }?.firstOrNull()
}

val kotlinGradlePluginApiJar = locateJar(
    relativeDir = "libraries/tools/kotlin-gradle-plugin-api/build/libs",
    predicate = {
        it.extension == "jar" &&
            !it.name.contains("-sources") &&
            !it.name.contains("-javadoc") &&
            it.name.startsWith("kotlin-gradle-plugin-api-") &&
            !Regex("-gradle\\d").containsMatchIn(it.name)
    },
    buildTask = ":libraries:tools:kotlin-gradle-plugin-api:jar",
)

val kotlinToolingCoreJar = locateJar(
    relativeDir = "libraries/tools/kotlin-tooling-core/build/libs",
    predicate = { it.extension == "jar" && !it.name.contains("-sources") && it.name.startsWith("kotlin-tooling-core-") },
    buildTask = ":libraries:tools:kotlin-tooling-core:jar",
)

val kotlinCompilerEmbeddableJar = listOf(
    "kotlin-compiler-embeddable/build/libs" to ":kotlin-compiler-embeddable:jar",
    "prepare/compiler-embeddable/build/libs" to ":prepare:compiler-embeddable:jar",
    "build/repo/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.3.0-wit.1" to "publish"
).firstNotNullOfOrNull { (relativeDir, _) ->
    locateJarOrNull(relativeDir) { file ->
        file.extension == "jar" && file.name.startsWith("kotlin-compiler-embeddable-") && !file.name.contains("-sources") && !file.name.contains("-javadoc")
    }
} ?: error(
    "Expected kotlin-compiler-embeddable jar in 'kotlin-compiler-embeddable/build/libs', 'prepare/compiler-embeddable/build/libs', or 'build/repo/...'; run './gradlew :kotlin-compiler-embeddable:jar' first."
)


repositories {
    maven { url = uri("https://redirector.kotlinlang.org/maven/kotlin-dependencies") }
    maven { url = uri("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev") }
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    implementation(files(kotlinGradlePluginApiJar))
    implementation(files(kotlinToolingCoreJar))
    implementation(files(kotlinCompilerEmbeddableJar))
}

gradlePlugin {
    plugins {
        create("witCompilerPlugin") {
            id = "org.jetbrains.kotlin.wit.gradle"
            implementationClass = "org.jetbrains.kotlin.wit.gradle.WitGradleSubplugin"
            displayName = "Kotlin WIT Compiler Plugin (Gradle)"
            description = "Wires WIT compiler plugin and options into Kotlin compilations"
        }
    }
}
