package org.jetbrains.kotlin.wit.codegen.core

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.wit.codegen.core.plan.BindingTargetPlan
import org.jetbrains.kotlin.wit.codegen.core.plan.WitCodegenPlanner
import org.jetbrains.kotlin.wit.model.BindingKind
import org.jetbrains.kotlin.wit.resolve.Wit

class WitCodegenPlannerTest {
    @Test
    fun buildPlanFromSchema() {
        val dir = Files.createTempDirectory("wit-plan-test")
        val witFile = dir.resolve("schema.wit")
        witFile.writeText(
            """
            package sample:plan;

            use api as helpers;

            interface api {
                @unstable(feature = experimental)
                resource gizmo {
                    constructor();
                }

                operate: func(x: stream<u8>) -> option<u32>;
            }

            world host {
                use helpers.{gizmo};
                import api;
            }

            world client {
                include host;
                export operate: func();
            }
            """.trimIndent(),
        )

        val schema = Wit.load(
            Wit.Options(
                roots = listOf(Wit.SourceRoot.File(witFile)),
                features = setOf("active", "experimental"),
            ),
        )!!

        val plan = WitCodegenPlanner.build(schema)

        assertEquals(setOf("active", "experimental"), plan.features)
        val pkg = plan.packages.single()
        assertEquals("sample:plan", pkg.id)
        assertTrue(pkg.uses.any { it.alias == "helpers" })

        val hostWorld = pkg.worlds.first { it.name == "host" }
        val imports = hostWorld.imports
        val apiImport = imports.first { it.kind == BindingKind.INTERFACE }
        assertTrue(apiImport.target is BindingTargetPlan.Interface)
        val constructors = hostWorld.constructors
        assertTrue(constructors.any { it.bindingName.startsWith("constructor:gizmo") })

        val clientWorld = pkg.worlds.first { it.name == "client" }
        assertTrue(clientWorld.includes.any { it.path is org.jetbrains.kotlin.wit.model.WitUsePath.Identifier })
        val exportNames = clientWorld.exports.map { it.name }
        assertTrue("operate" in exportNames)
    }
}

