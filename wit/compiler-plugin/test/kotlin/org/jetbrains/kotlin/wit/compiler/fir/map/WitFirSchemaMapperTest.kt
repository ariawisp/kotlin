package org.jetbrains.kotlin.wit.compiler.fir.map

import kotlin.io.path.Path
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.wit.compiler.fir.WitFirSchemaMapper
import org.jetbrains.kotlin.wit.compiler.schema.BindingKind
import org.jetbrains.kotlin.wit.compiler.schema.FunctionKind
import org.jetbrains.kotlin.wit.compiler.schema.WitPackageMetadata
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeBinding
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeFunction
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeInterface
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimePackage
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeResource
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeSchema
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeType
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeWorld
import org.jetbrains.kotlin.wit.compiler.schema.WitSchemaIndex
import org.jetbrains.kotlin.wit.compiler.schema.WitSchemaSource
import org.jetbrains.kotlin.wit.compiler.schema.WitWorldMetadata

class WitFirSchemaMapperTest {

    @Test
    fun mapBuildsWorldMetadataForRuntimeSchema() {
        val schemaIndex = sampleSchemaIndex()
        val mapping = WitFirSchemaMapper(schemaIndex).map()

        val classId = ClassId(FqName("wit.generated.sample"), Name.identifier("Sample"))
        val metadata = mapping.worldDeclarations[classId]

        assertTrue(classId in mapping.topLevelClassIds)
        assertEquals(FqName("wit.generated.sample"), metadata?.packageFqName)
        assertEquals("perform", metadata?.importBindings?.keys?.single()?.asString())
    }

    private fun sampleSchemaIndex(): WitSchemaIndex {
        val runtimeFunction = WitRuntimeFunction(
            name = "perform",
            parameters = listOf(WitRuntimeType(label = "value", typeRef = "string")),
            results = emptyList(),
            kind = FunctionKind.METHOD,
            isAsync = false,
            usesStreams = false,
        )
        val runtimeBinding = WitRuntimeBinding(
            name = "perform",
            target = "Iface",
            bindingKind = BindingKind.FUNCTION,
            signature = runtimeFunction,
        )
        val runtimeInterface = WitRuntimeInterface(
            name = "Iface",
            stability = null,
            resources = listOf(
                WitRuntimeResource(
                    name = "Thing",
                    stability = null,
                    ownHandleType = "i32",
                    borrowHandleType = null,
                ),
            ),
            functions = listOf(runtimeFunction),
        )
        val runtimeWorld = WitRuntimeWorld(
            name = "SampleWorld",
            stability = null,
            imports = listOf(runtimeBinding),
            exports = emptyList(),
            constructors = emptyList(),
        )
        val runtimePackage = WitRuntimePackage(
            id = "sample:component@1.0.0",
            source = WitSchemaSource.Directory(Path("/tmp"), includeRoots = emptyList()),
            includes = emptyList(),
            sourceFiles = emptyList(),
            metadata = WitPackageMetadata(
                name = "sample:component",
                interfaces = emptyList(),
                worlds = listOf(WitWorldMetadata("SampleWorld", null)),
            ),
            interfaces = listOf(runtimeInterface),
            worlds = listOf(runtimeWorld),
        )
        val runtimeSchema = WitRuntimeSchema(
            packages = listOf(runtimePackage),
            features = emptySet(),
            sources = listOf(WitSchemaSource.Directory(Path("/tmp"), includeRoots = emptyList())),
        )
        return WitSchemaIndex(
            witPackages = emptyList(),
            jsonSchemas = emptyList(),
            enabledFeatures = emptySet(),
            packageMetadata = listOf(runtimePackage.metadata!!),
            runtimeSchema = runtimeSchema,
        )
    }
}
