import org.gradle.api.publish.maven.MavenPublication

plugins {
    `maven-publish`
    signing
}

description = "Legacy alias for the Kotlin Standard Library WASI preview2 component build"

publishing {
    publications.register<MavenPublication>("kotlinStdlibWasmComponent") {
        artifactId = "kotlin-stdlib-wasm-component"
        pom {
            packaging = "pom"
            name.set("Kotlin Standard Library for WebAssembly (component alias)")
            description.set("Deprecated alias for kotlin-stdlib-wasm-wasi; depends on the canonical WASI preview2 stdlib.")
        }
        pom.withXml {
            val pomNode = asNode()
            val distributionManagement = pomNode.appendNode("distributionManagement")
            val relocation = distributionManagement.appendNode("relocation")
            relocation.appendNode("groupId", "org.jetbrains.kotlin")
            relocation.appendNode("artifactId", "kotlin-stdlib-wasm-wasi")
            relocation.appendNode("version", project.version.toString())
            relocation.appendNode("message", "kotlin-stdlib-wasm-component has been replaced by kotlin-stdlib-wasm-wasi.")

            val dependencies = pomNode.appendNode("dependencies")
            val dependency = dependencies.appendNode("dependency")
            dependency.appendNode("groupId", "org.jetbrains.kotlin")
            dependency.appendNode("artifactId", "kotlin-stdlib-wasm-wasi")
            dependency.appendNode("version", project.version.toString())
            dependency.appendNode("scope", "compile")
        }
    }

    repositories {
        maven {
            url = uri("${rootDir}/build/repo")
        }
    }
}
