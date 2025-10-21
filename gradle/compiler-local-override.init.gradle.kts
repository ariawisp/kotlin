// Force builds to use the locally-built compiler embeddable jar, without republishing plugins/BOMs.
// Usage:
//   ./gradlew <tasks> -Pbootstrap.local=false -PuseLocalKgpPlugin=true \
//     -I gradle/compiler-local-override.init.gradle.kts

import org.gradle.kotlin.dsl.configure

gradle.allprojects {
    afterEvaluate {
        // Replace the compiler classpath with the local jar if present
        val compilerJar = rootProject.layout.projectDirectory.file(
            "build/repo/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.3.0-wit.1/kotlin-compiler-embeddable-2.3.0-wit.1.jar"
        ).asFile
        if (compilerJar.exists()) {
            configurations.matching { it.name == "kotlinCompilerClasspath" }.configureEach {
                with(dependencies) {
                    clear()
                    add(project.dependencies.create(project.files(compilerJar)))
                }
            }
        }
    }
}

