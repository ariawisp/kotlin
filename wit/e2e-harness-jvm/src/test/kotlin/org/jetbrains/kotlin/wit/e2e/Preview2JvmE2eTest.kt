package org.jetbrains.kotlin.wit.e2e

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.sequences.sequence
import kotlinx.metadata.klib.KlibModuleMetadata
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

    @Test
    fun introspectsPreview2GeneratedModule() {
        val module = loadPreview2ModuleMetadata()
        val fragments = module.fragments.mapNotNull { it.fqName }.sorted()
        assertTrue(fragments.isNotEmpty(), "Expected preview2 module to declare packages")
        println("Preview2 fragments: ${fragments.joinToString()}")
        val randomFragments = fragments.filter { it.startsWith("wit.generated.wasi.random") }
        assertTrue(randomFragments.isNotEmpty(), "Expected wasi.random fragments, found none")
    }

    private fun loadPreview2ModuleMetadata(): KlibModuleMetadata {
        val klib = repoPath(
            "libraries",
            "stdlib",
            "build",
            "wit-klibs",
            "wasi-preview2",
            "kotlin-wasm-wasi-preview2.klib",
        )
        assertTrue(Files.isRegularFile(klib), "Expected preview2 klib at $klib")
        ZipFile(klib.toFile()).use { zip ->
            val provider = object : KlibModuleMetadata.MetadataLibraryProvider {
                private val moduleEntry = "default/linkdata/module"
                override val moduleHeaderData: ByteArray =
                    zip.readBytes(moduleEntry) ?: error("Missing module header in preview2 klib")

                override fun packageMetadataParts(fqName: String): Set<String> {
                    val prefix = "default/linkdata/package_$fqName/"
                    return zip.entries().asSequence()
                        .map { it.name }
                        .filter { it.startsWith(prefix) && it.endsWith(".knm") }
                        .map { it.removePrefix(prefix).removeSuffix(".knm") }
                        .toSortedSet()
                }

                override fun packageMetadata(fqName: String, partName: String): ByteArray {
                    val entryName = "default/linkdata/package_$fqName/$partName.knm"
                    return zip.readBytes(entryName)
                        ?: error("Missing package fragment $entryName in preview2 klib")
                }

                private fun ZipFile.readBytes(entryName: String): ByteArray? {
                    val entry = getEntry(entryName) ?: return null
                    return getInputStream(entry).use { it.readBytes() }
                }

                private fun <T> java.util.Enumeration<T>.asSequence(): Sequence<T> = sequence {
                    while (hasMoreElements()) yield(nextElement())
                }
            }
            return KlibModuleMetadata.read(provider)
        }
    }

    private fun repoPath(vararg segments: String): Path {
        val projectDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val repoRoot = projectDir.parent?.parent ?: projectDir
        return segments.fold(repoRoot) { acc, segment -> acc.resolve(segment) }
    }
}
