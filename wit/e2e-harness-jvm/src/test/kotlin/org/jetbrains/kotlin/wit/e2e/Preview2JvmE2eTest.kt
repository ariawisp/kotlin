package org.jetbrains.kotlin.wit.e2e

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.kotlin.wit.resolve.Wit

class Preview2JvmE2eTest {
    @Test
    fun loadsOfficialWasiRandomWorldMetadata() {
        val witDir = repoPath("libraries", "stdlib", "wasm", "wasi", "wit-upstream", "random")
        assertTrue(Files.isDirectory(witDir), "Expected official WASI random WIT directory: $witDir")

        val schema = assertNotNull(
            Wit.load(
                Wit.Options(
                    roots = listOf(Wit.SourceRoot.Directory(witDir)),
                    features = setOf("resources"),
                )
            ),
        )

        val packageLabels = schema.packages.map { entry ->
            val namespace = java.lang.String.valueOf(entry.id.namespace)
            val name = java.lang.String.valueOf(entry.id.name)
            namespace to name
        }
        val pkg = schema.packages.firstOrNull { entry ->
            val namespace = java.lang.String.valueOf(entry.id.namespace)
            val name = java.lang.String.valueOf(entry.id.name)
            namespace == "wasi" && name == "random"
        } ?: error(
            "Unable to locate wasi:random package. Loaded packages: ${
                packageLabels.joinToString { (ns, name) -> "$ns:$name" }
            }",
        )
        val versionText = pkg.id.version ?: error("Expected random package version")
        val versionString = Regex("""\d+(?:\.\d+)+""")
            .find(versionText)
            ?.value
            ?: versionText
        assertEquals("0.2.8", versionString)

        val worldLabels = pkg.worlds.map { java.lang.String.valueOf(it.name) }
        val world = pkg.worlds.firstOrNull { java.lang.String.valueOf(it.name) == "imports" }
            ?: error("Unable to locate imports world in wasi:random. Worlds: $worldLabels")
        assertEquals(3, world.imports.size)
        assertTrue(world.imports.any { it.name == "random.get-random-u64" })
        assertTrue(world.imports.any { it.name == "insecure.get-insecure-random-u64" })
        assertTrue(world.imports.any { it.name == "insecure-seed.insecure-seed" })
    }

    @Test
    fun introspectsPreview2GeneratedModule() {
        val metadata = Preview2MetadataIntrospector.loadPreview2Metadata(repoRoot())
        assertTrue(metadata.worlds.isNotEmpty(), "Expected preview2 module to declare generated worlds")

        val randomImports = metadata.worlds.firstOrNull { world ->
            world.worldName == "imports" && world.packageId.startsWith("wasi:random")
        }
        assertNotNull(randomImports, "Expected wasi:random/imports world in Preview-2 metadata")
        assertNotNull(randomImports.driverClassName, "Expected generated driver class for wasi:random/imports")
        assertNotNull(randomImports.companionClassName, "Expected companion class for wasi:random/imports")

        val bindingNames = randomImports.bindings.map { it.bindingName }.toSet()
        assertTrue(
            bindingNames.containsAll(
                listOf(
                    "random.get-random-u64",
                    "insecure.get-insecure-random-u64",
                    "insecure-seed.insecure-seed",
                )
            ),
            "Expected random world bindings in metadata, found $bindingNames",
        )
    }

    private fun repoPath(vararg segments: String): Path {
        val projectDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val repoRoot = projectDir.parent?.parent ?: projectDir
        return segments.fold(repoRoot) { acc, segment -> acc.resolve(segment) }
    }

    private fun repoRoot(): Path = repoPath()

}
