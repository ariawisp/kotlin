package org.jetbrains.kotlin.wit.codegen.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.kotlin.wit.codegen.core.schema.BindingKind

class WitAstSchemaLoaderTest {
    @Test
    fun loadsEmptyPackage() {
        val root = Files.createTempDirectory("wit-core-empty")
        root.resolve("empty.wit").writeText("package foo:demo;")

        val loader = WitAstSchemaLoader()
        val schema = loader.load(
            WitAstSchemaLoader.Options(
                rootPaths = listOf(root),
                includePaths = emptyList(),
                features = setOf("active"),
            ),
        )

        assertNotNull(schema)
        val pkg = schema.packages.single()
        assertEquals("foo:demo", pkg.id)
        assertTrue(pkg.interfaces.isEmpty())
        assertTrue(pkg.worlds.isEmpty())
    }

    @Test
    fun loadsWorldImportInterfaceBinding() {
        val root = Files.createTempDirectory("wit-core-world-iface")
        val wit = """
            package sample:demo;
            interface api { op: func(); }
            world w { import api; }
        """.trimIndent()
        root.resolve("schema.wit").writeText(wit)

        val loader = WitAstSchemaLoader()
        val schema = loader.load(
            WitAstSchemaLoader.Options(
                rootPaths = listOf(root),
                features = setOf("active"),
            ),
        )!!

        val pkg = schema.packages.single()
        assertEquals("sample:demo", pkg.id)
        val world = pkg.worlds.single { it.name == "w" }
        assertTrue(world.imports.any { it.bindingKind == BindingKind.INTERFACE && it.target == "api" })
    }

    @Test
    fun resourceConstructorIsVisibleOnWorld() {
        val root = Files.createTempDirectory("wit-core-ctor")
        val wit = """
            package sample:res;
            interface api {
                resource widget { constructor(arg: string); }
            }
            world w { import api; }
        """.trimIndent()
        root.resolve("schema.wit").writeText(wit)

        val loader = WitAstSchemaLoader()
        val schema = loader.load(
            WitAstSchemaLoader.Options(rootPaths = listOf(root), features = setOf("active")),
        )!!

        val pkg = schema.packages.single()
        val world = pkg.worlds.single { it.name == "w" }
        assertTrue(world.constructors.any { it.bindingName.startsWith("constructor:widget") })
        val ctor = world.constructors.first { it.bindingName.startsWith("constructor:widget") }
        assertTrue(ctor.signature.parameters.any { it.typeRef == "string" })
    }

    @Test
    fun detectsStreamsAndAsync() {
        val root = Files.createTempDirectory("wit-core-streams")
        val wit = """
            package test:streams;
            interface io {
              read: func() -> stream<u8>;
              a: async func(x: u32) -> u64;
            }
            world w { import io; export x: async func(); }
        """.trimIndent()
        root.resolve("schema.wit").writeText(wit)

        val loader = WitAstSchemaLoader()
        val schema = loader.load(
            WitAstSchemaLoader.Options(rootPaths = listOf(root), features = setOf("active")),
        )!!

        val pkg = schema.packages.single()
        val iface = pkg.interfaces.single { it.name == "io" }
        val read = iface.functions.single { it.name == "read" }
        assertTrue(read.usesStreams)
        val afn = iface.functions.single { it.name == "a" }
        assertTrue(afn.isAsync)
        val world = pkg.worlds.single { it.name == "w" }
        assertTrue(world.exports.any { it.bindingKind == BindingKind.FUNCTION && it.signature?.isAsync == true })
    }
}
