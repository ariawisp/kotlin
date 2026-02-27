package org.jetbrains.kotlin.wit.e2e

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.kotlin.wit.resolve.Wit
import org.jetbrains.kotlin.wit.e2e.Preview2ModuleMetadata
import org.jetbrains.kotlin.wit.e2e.Preview2BindingMetadata
import org.jetbrains.kotlin.wit.e2e.Preview2WorldMetadata
import org.jetbrains.kotlin.wit.runtime.ComponentRuntime
import org.jetbrains.kotlin.wit.runtime.DefaultComponentRuntime
import org.jetbrains.kotlin.wit.runtime.WitBindingDirection
import org.jetbrains.kotlin.wit.runtime.WitBindingKind
import org.jetbrains.kotlin.wit.runtime.pendingBindingDelegate

class Preview2JvmE2eTest {
    @Test
    fun loadsOfficialWasiRandomWorldMetadata() {
        val witDir = repoPath("libraries", "stdlib", "wasm", "wasi", "wit-upstream", "random")
        assertTrue(Files.isDirectory(witDir), "Expected official WASI random WIT directory: $witDir")

        val schema = assertNotNull(
            Wit.load(
                Wit.Options(
                    roots = listOf(Wit.SourceRoot.Directory(witDir)),
                    features = setOf("active", "resources"),
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
        assertTrue(world.imports.any { it.name == "random" })
        assertTrue(world.imports.any { it.name == "insecure" })
        assertTrue(world.imports.any { it.name == "insecure-seed" })
    }

    @Test
    fun hostRuntimeHandlesRandomImports() {
        val metadata = Preview2MetadataIntrospector.loadPreview2Metadata(repoRoot())
        metadata.worlds.forEach { world ->
            println("World ${world.packageId}/${world.worldName}: ${world.bindings.map { it.bindingName }}")
        }
        val randomImports = metadata.worlds.firstOrNull { world ->
            world.worldName == "imports" && world.packageId.startsWith("wasi:random")
        } ?: error("Preview-2 metadata missing wasi:random/imports world")

        val runtime = DefaultComponentRuntime()
        registerRandomHandlers(runtime, randomImports)

        val bytesDelegate = pendingBindingDelegate(
            packageId = randomImports.packageId,
            worldName = randomImports.worldName,
            bindingName = "random.get-random-bytes",
            direction = WitBindingDirection.IMPORT,
            kind = WitBindingKind.FUNCTION,
            runtimeTarget = "",
            isAsync = false,
            usesStreams = false,
        )
        val bytes = runtime.dispatchBinding(bytesDelegate, 16L) as ByteArray
        assertEquals(16, bytes.size)

        val u64Delegate = pendingBindingDelegate(
            packageId = randomImports.packageId,
            worldName = randomImports.worldName,
            bindingName = "random.get-random-u64",
            direction = WitBindingDirection.IMPORT,
            kind = WitBindingKind.FUNCTION,
            runtimeTarget = "",
            isAsync = false,
            usesStreams = false,
        )
        val randomValue = runtime.dispatchBinding(u64Delegate) as Long
        assertTrue(randomValue != 0L)

        val insecureBytesDelegate = pendingBindingDelegate(
            packageId = randomImports.packageId,
            worldName = randomImports.worldName,
            bindingName = "insecure.get-insecure-random-bytes",
            direction = WitBindingDirection.IMPORT,
            kind = WitBindingKind.FUNCTION,
            runtimeTarget = "",
            isAsync = false,
            usesStreams = false,
        )
        val insecureBytes = runtime.dispatchBinding(insecureBytesDelegate, 8L) as ByteArray
        assertEquals(8, insecureBytes.size)

        val seedDelegate = pendingBindingDelegate(
            packageId = randomImports.packageId,
            worldName = randomImports.worldName,
            bindingName = "insecure-seed.insecure-seed",
            direction = WitBindingDirection.IMPORT,
            kind = WitBindingKind.FUNCTION,
            runtimeTarget = "",
            isAsync = false,
            usesStreams = false,
        )
        val seed = runtime.dispatchBinding(seedDelegate) as Pair<*, *>
        assertEquals(2, listOfNotNull(seed.first, seed.second).size)
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
        println("Metadata bindings: $bindingNames")
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

    private fun registerRandomHandlers(
        runtime: ComponentRuntime,
        world: Preview2WorldMetadata,
    ) {
        val rng = Random(0x4b4f544c494eL)
        world.bindings.forEach { binding: Preview2BindingMetadata ->
            when (binding.bindingName) {
                "random.get-random-bytes" -> runtime.registerImportHandler(world.packageId, world.worldName, binding.bindingName) { args: Array<out Any?> ->
                    val len = args.firstOrNull()?.let { (it as Number).toLong() } ?: 0L
                    require(len <= Int.MAX_VALUE) { "Requested random byte length $len exceeds Int.MAX_VALUE" }
                    ByteArray(len.toInt()).also(rng::nextBytes)
                }
                "random.get-random-u64" -> runtime.registerImportHandler(world.packageId, world.worldName, binding.bindingName) { _: Array<out Any?> ->
                    rng.nextLong()
                }
                "insecure.get-insecure-random-bytes" -> runtime.registerImportHandler(world.packageId, world.worldName, binding.bindingName) { args: Array<out Any?> ->
                    val len = args.firstOrNull()?.let { (it as Number).toLong() } ?: 0L
                    require(len <= Int.MAX_VALUE) { "Requested insecure random byte length $len exceeds Int.MAX_VALUE" }
                    ByteArray(len.toInt()).also(rng::nextBytes)
                }
                "insecure.get-insecure-random-u64" -> runtime.registerImportHandler(world.packageId, world.worldName, binding.bindingName) { _: Array<out Any?> ->
                    rng.nextLong()
                }
                "insecure-seed.insecure-seed" -> runtime.registerImportHandler(world.packageId, world.worldName, binding.bindingName) { _: Array<out Any?> ->
                    rng.nextLong() to rng.nextLong()
                }
                else -> runtime.registerImportHandler(world.packageId, world.worldName, binding.bindingName) { _: Array<out Any?> ->
                    Unit
                }
            }
        }
    }

}
