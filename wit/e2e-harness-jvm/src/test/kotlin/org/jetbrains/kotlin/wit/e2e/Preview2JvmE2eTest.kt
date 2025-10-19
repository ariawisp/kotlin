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

        val pkg = schema.packages.single { it.id.namespace == "wasi" && it.id.name == "random" }
        assertEquals("0.2.8", pkg.id.version)

        val world = pkg.worlds.single { it.name == "imports" }
        assertEquals(3, world.imports.size)
        assertTrue(world.imports.any { it.name == "random.get-random-u64" })
        assertTrue(world.imports.any { it.name == "insecure.get-insecure-random-u64" })
        assertTrue(world.imports.any { it.name == "insecure-seed.insecure-seed" })
    }

    private fun repoPath(vararg segments: String): Path {
        val projectDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val repoRoot = projectDir.parent?.parent ?: projectDir
        return segments.fold(repoRoot) { acc, segment -> acc.resolve(segment) }
    }
}
