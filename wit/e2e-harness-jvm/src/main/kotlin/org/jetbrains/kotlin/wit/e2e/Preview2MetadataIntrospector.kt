@file:OptIn(ExperimentalAnnotationsInMetadata::class)

package org.jetbrains.kotlin.wit.e2e

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile
import java.util.Enumeration
import java.util.Comparator
import kotlin.collections.buildList
import kotlin.metadata.ExperimentalAnnotationsInMetadata
import kotlin.metadata.KmAnnotation
import kotlin.metadata.KmAnnotationArgument
import kotlin.metadata.KmClass
import kotlin.metadata.KmConstructor
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.internal.common.KmModuleFragment
import kotlinx.metadata.klib.KlibModuleMetadata
import kotlinx.metadata.klib.fqName
import org.jetbrains.kotlin.wit.model.BindingKind as WitBindingKind
import org.jetbrains.kotlin.wit.model.BindingTarget
import org.jetbrains.kotlin.wit.model.FuncKind
import org.jetbrains.kotlin.wit.model.PackageId
import org.jetbrains.kotlin.wit.model.WitBinding
import org.jetbrains.kotlin.wit.model.WitConstructor
import org.jetbrains.kotlin.wit.model.WitInterface
import org.jetbrains.kotlin.wit.model.WitPackage
import org.jetbrains.kotlin.wit.resolve.Wit

/**
 * Introspects the generated Preview-2 bindings `klib` and extracts the minimal metadata the harness
 * needs to wire drivers, bindings, and resource helpers. Both the JVM test suite and Gradle tasks
 * reuse this helper so we have a single source of truth for parsing the synthetic annotations.
 */
object Preview2MetadataIntrospector {
    private val preview2KlibPathSegments = arrayOf(
        "libraries",
        "stdlib",
        "build",
        "wit-klibs",
        "wasi-preview2",
        "kotlin-wasm-wasi-preview2.klib",
    )

    private val preview2WitRootSegments = arrayOf(
        "libraries",
        "stdlib",
        "wasm",
        "wasi",
        "wit-upstream",
    )

    private val VERSION_SPEC_REGEX = Regex("value=([^,)]+)")

    private const val DRIVER_OBJECT_SIMPLE_NAME: String = "__WitDriver"
    private const val WIT_RUNTIME_PACKAGE: String = "org.jetbrains.kotlin.wit.runtime"
    private const val WIT_WORLD_ANNOTATION: String = "$WIT_RUNTIME_PACKAGE.WitWorld"
    private const val WIT_BINDING_ANNOTATION: String = "$WIT_RUNTIME_PACKAGE.WitBinding"
    private const val WIT_RESOURCE_ANNOTATION: String = "$WIT_RUNTIME_PACKAGE.WitResource"
    private const val WIT_CONSTRUCTOR_ANNOTATION: String = "$WIT_RUNTIME_PACKAGE.WitConstructor"

    /**
     * Loads the `kotlin-wasm-wasi-preview2.klib` from the repository root and parses its metadata.
     */
    fun loadPreview2Metadata(repoRoot: Path): Preview2ModuleMetadata {
        val klibRelative = Paths.get(
            preview2KlibPathSegments.first(),
            *preview2KlibPathSegments.drop(1).toTypedArray(),
        )
        val klibPath = repoRoot.resolve(klibRelative).normalize()
        val metadata = runCatching { loadFromKlib(klibPath) }.getOrNull()
        if (metadata != null && metadata.worlds.isNotEmpty()) {
            return metadata
        }
        val fallback = loadFromWitWorkspace(repoRoot, metadata?.moduleName)
        return if (metadata == null) fallback else metadata.copy(worlds = fallback.worlds)
    }

    /**
     * Loads the provided `.klib` file and parses its metadata into a strongly typed snapshot.
     */
    fun loadFromKlib(klibPath: Path): Preview2ModuleMetadata {
        require(Files.isRegularFile(klibPath)) {
            "Preview-2 klib not found at $klibPath (did you run :kotlin-stdlib:generateWasiPreview2Klib?)"
        }

        ZipFile(klibPath.toFile()).use { zip ->
            val provider = object : KlibModuleMetadata.MetadataLibraryProvider {
                private val moduleEntry = "default/linkdata/module"

                override val moduleHeaderData: ByteArray =
                    zip.readBytes(moduleEntry) ?: error("Missing module header in Preview-2 klib")

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
                        ?: error("Missing package fragment $entryName in Preview-2 klib")
                }

                private fun ZipFile.readBytes(entryName: String): ByteArray? {
                    val entry = getEntry(entryName) ?: return null
                    return getInputStream(entry).use { it.readBytes() }
                }
            }

            val module = KlibModuleMetadata.read(provider)
            return extractPreview2Metadata(module)
        }
    }

    private fun loadFromWitWorkspace(repoRoot: Path, moduleNameOverride: String?): Preview2ModuleMetadata {
        val witRelative = Paths.get(
            preview2WitRootSegments.first(),
            *preview2WitRootSegments.drop(1).toTypedArray(),
        )
        val witRoot = repoRoot.resolve(witRelative).normalize()
        if (!Files.isDirectory(witRoot)) {
            return Preview2ModuleMetadata(moduleNameOverride ?: "<kotlin-wasm-wasi-preview2>", emptyList())
        }
        val moduleName = moduleNameOverride ?: "<kotlin-wasm-wasi-preview2>"
        val worldMap = linkedMapOf<String, Preview2WorldMetadata>()
        Files.list(witRoot).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .sorted(Comparator.comparing(Path::toString))
                .forEach { packageDir ->
                    val schema = Wit.load(
                        Wit.Options(
                            roots = listOf(Wit.SourceRoot.Directory(packageDir)),
                            features = setOf("active", "resources"),
                        )
                    ) ?: return@forEach
                    schema.packages.flatMap { pkg -> buildWorldMetadata(pkg) }.forEach { world ->
                        worldMap[world.worldClassName] = world
                    }
                }
        }
        val worlds = worldMap.values.sortedBy { it.worldClassName }
        return Preview2ModuleMetadata(moduleName, worlds)
    }

    private fun buildWorldMetadata(pkg: WitPackage): List<Preview2WorldMetadata> {
        if (pkg.worlds.isEmpty()) return emptyList()
        val packageId = buildPackageId(pkg.id)
        val interfaceDetails: Map<String, WitInterface> = pkg.interfaces.associateBy { it.name }
        return pkg.worlds.map { world ->
            val worldClassName = buildWorldClassName(pkg, world.name)
            val companionClassName = "$worldClassName.Companion"
            val driverClassName = "$companionClassName.__WitDriver"

            val bindings = mutableListOf<Preview2BindingMetadata>()
            val resources = mutableListOf<Preview2ResourceMetadata>()
            val bindingDirectionByName = mutableMapOf<String, BindingDirection>()

            fun registerBinding(binding: Preview2BindingMetadata) {
                bindings += binding
                bindingDirectionByName[binding.bindingName] = binding.direction
                bindingDirectionByName[binding.bindingName.substringBeforeLast('.', binding.bindingName)] = binding.direction
            }

            world.imports.forEach { binding ->
                val expansion = expandBinding(worldClassName, binding, BindingDirection.IMPORT, interfaceDetails)
                expansion.bindings.forEach(::registerBinding)
                resources += expansion.resources
            }
            world.exports.forEach { binding ->
                val expansion = expandBinding(worldClassName, binding, BindingDirection.EXPORT, interfaceDetails)
                expansion.bindings.forEach(::registerBinding)
                resources += expansion.resources
            }

            val constructors = world.constructors.map { constructor ->
                fromWitConstructor(worldClassName, constructor, bindingDirectionByName[constructor.bindingName] ?: BindingDirection.IMPORT)
            }

            Preview2WorldMetadata(
                packageId = packageId,
                worldName = world.name,
                worldClassName = worldClassName,
                companionClassName = companionClassName,
                driverClassName = driverClassName,
                bindings = bindings.sortedBy { it.bindingName },
                resources = resources.sortedBy { it.resourceName },
                constructors = constructors.sortedBy { it.bindingName },
            )
        }
    }

    private fun buildPackageId(id: PackageId): String {
        val version = normalizeVersion(id.version)
        return buildString {
            append(id.namespace)
            append(":")
            append(id.name)
            if (version != null) {
                append('@')
                append(version)
            }
        }
    }

    private fun normalizeVersion(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val valueMatch = VERSION_SPEC_REGEX.find(raw)
        return valueMatch?.groupValues?.get(1) ?: raw
    }

    private fun buildWorldClassName(pkg: WitPackage, worldName: String): String {
        val segments = mutableListOf("wit", "generated")
        if (pkg.id.namespace.isNotBlank()) segments += sanitizeSegment(pkg.id.namespace)
        if (pkg.id.name.isNotBlank()) segments += sanitizeSegment(pkg.id.name)
        segments += sanitizeSegment(worldName)
        return segments.joinToString(".")
    }

    private fun sanitizeSegment(raw: String): String = raw.replace('-', '_').replace('.', '_')

    private data class ExpandedBinding(
        val bindings: List<Preview2BindingMetadata>,
        val resources: List<Preview2ResourceMetadata>,
    )

    private fun expandBinding(
        worldClassName: String,
        binding: WitBinding,
        direction: BindingDirection,
        interfaceDetails: Map<String, WitInterface>,
    ): ExpandedBinding {
        return when (binding.kind) {
            WitBindingKind.FUNCTION -> {
                val functionName = binding.signature?.name?.takeIf { it.isNotBlank() }
                    ?: (binding.target as? BindingTarget.Function)?.name
                    ?: binding.name
                val bindingMeta = Preview2BindingMetadata(
                    ownerClassName = worldClassName,
                    declarationName = functionName,
                    declarationKind = BindingDeclarationKind.FUNCTION,
                    bindingName = "${binding.name}.$functionName",
                    direction = direction,
                    kind = BindingKind.FUNCTION,
                    runtimeTarget = null,
                    interfaceName = binding.name,
                    resourceName = null,
                    isAsync = binding.signature?.isAsync ?: false,
                    usesStreams = binding.signature?.usesStreams ?: false,
                )
                ExpandedBinding(listOf(bindingMeta), emptyList())
            }
            WitBindingKind.INTERFACE -> {
                val iface = interfaceDetails[binding.name]
                if (iface != null) {
                    val functionBindings = iface.functions.map { function ->
                        Preview2BindingMetadata(
                            ownerClassName = worldClassName,
                            declarationName = function.name,
                            declarationKind = BindingDeclarationKind.FUNCTION,
                            bindingName = "${binding.name}.${function.name}",
                            direction = direction,
                            kind = BindingKind.FUNCTION,
                            runtimeTarget = null,
                            interfaceName = binding.name,
                            resourceName = null,
                            isAsync = function.isAsync,
                            usesStreams = function.usesStreams,
                        )
                    }
                    val resourceMetadata = iface.resources.map { resource ->
                        Preview2ResourceMetadata(
                            ownerClassName = worldClassName,
                            declarationName = resource.name,
                            interfaceName = binding.name,
                            resourceName = resource.name,
                            ownHandleType = resource.ownHandle?.resource,
                            borrowHandleType = resource.borrowHandle?.resource,
                        )
                    }
                    ExpandedBinding(functionBindings, resourceMetadata)
                } else {
                    val fallback = Preview2BindingMetadata(
                        ownerClassName = worldClassName,
                        declarationName = binding.name,
                        declarationKind = BindingDeclarationKind.PROPERTY,
                        bindingName = "${binding.name}.${binding.name}",
                        direction = direction,
                        kind = BindingKind.INTERFACE,
                        runtimeTarget = null,
                        interfaceName = binding.name,
                        resourceName = null,
                        isAsync = false,
                        usesStreams = false,
                    )
                    ExpandedBinding(listOf(fallback), emptyList())
                }
            }
            WitBindingKind.RESOURCE -> {
                val resourceName = (binding.target as? BindingTarget.Resource)?.name
                val iface = interfaceDetails[binding.name]
                val resourceDetails = resourceName?.let { name -> iface?.resources?.firstOrNull { it.name == name } }
                val bindingMeta = Preview2BindingMetadata(
                    ownerClassName = worldClassName,
                    declarationName = resourceName ?: binding.name,
                    declarationKind = BindingDeclarationKind.PROPERTY,
                    bindingName = "${binding.name}.${resourceName ?: binding.name}",
                    direction = direction,
                    kind = BindingKind.RESOURCE,
                    runtimeTarget = null,
                    interfaceName = binding.name,
                    resourceName = resourceName,
                    isAsync = false,
                    usesStreams = false,
                )
                val resourceMeta = resourceName?.let { name ->
                    Preview2ResourceMetadata(
                        ownerClassName = worldClassName,
                        declarationName = name,
                        interfaceName = binding.name,
                        resourceName = name,
                        ownHandleType = resourceDetails?.ownHandle?.resource,
                        borrowHandleType = resourceDetails?.borrowHandle?.resource,
                    )
                }
                ExpandedBinding(listOf(bindingMeta), listOfNotNull(resourceMeta))
            }
        }
    }

    private fun fromWitConstructor(
        worldClassName: String,
        constructor: WitConstructor,
        direction: BindingDirection,
    ): Preview2ConstructorMetadata {
        val declarationKind = if (constructor.signature.kind == FuncKind.CONSTRUCTOR) {
            ConstructorDeclarationKind.CONSTRUCTOR
        } else {
            ConstructorDeclarationKind.FUNCTION
        }
        val declarationName = constructor.signature.name.ifBlank { constructor.bindingName }
        return Preview2ConstructorMetadata(
            ownerClassName = worldClassName,
            declarationName = declarationName,
            declarationKind = declarationKind,
            bindingName = constructor.bindingName,
            direction = direction,
        )
    }

    private fun extractPreview2Metadata(module: KlibModuleMetadata): Preview2ModuleMetadata {
        val classIndex = buildClassIndex(module.fragments)
        val worlds = classIndex.values
            .filter { it.kmClass.findAnnotation(WIT_WORLD_ANNOTATION) != null }
            .map { worldRecord -> buildWorldMetadata(worldRecord, classIndex) }
            .sortedBy { it.worldClassName }
        return Preview2ModuleMetadata(module.name, worlds)
    }

    private fun buildClassIndex(fragments: List<KmModuleFragment>): Map<String, ClassRecord> {
        val index = linkedMapOf<String, ClassRecord>()
        fragments.forEach { fragment ->
            val packageName = fragment.fqName?.takeIf { it.isNotEmpty() }?.replace('/', '.')
            fragment.classes.forEach { kmClass ->
                val fqName = computeClassFqName(packageName, kmClass)
                index[fqName] = ClassRecord(fqName, kmClass)
            }
        }
        return index
    }

    private fun computeClassFqName(packageName: String?, kmClass: KmClass): String {
        val className = kmClass.name.replace('/', '.')
        val effectivePackage = packageName ?: className.substringBeforeLast('.', "").takeIf { it.isNotEmpty() }
        return when {
            effectivePackage == null -> className
            className.startsWith("$effectivePackage.") -> className
            else -> "$effectivePackage.$className"
        }
    }

    private fun buildWorldMetadata(
        worldRecord: ClassRecord,
        classIndex: Map<String, ClassRecord>,
    ): Preview2WorldMetadata {
        val worldAnnotation =
            worldRecord.kmClass.findAnnotation(WIT_WORLD_ANNOTATION)
                ?: error("World class ${worldRecord.fqName} missing @WitWorld annotation")
        val packageId = worldAnnotation.stringArg("packageId") ?: error("Missing packageId on @WitWorld")
        val worldName = worldAnnotation.stringArg("worldName") ?: error("Missing worldName on @WitWorld")

        val worldFqName = worldRecord.fqName
        val companionName = worldRecord.kmClass.companionObject ?: "Companion"
        val companionFqName = "$worldFqName.$companionName"
        val companionRecord = classIndex[companionFqName]
        val driverFqName = "$companionFqName.$DRIVER_OBJECT_SIMPLE_NAME"
        val driverRecord = classIndex[driverFqName]

        val nestedRecords = classIndex.values.filter { record ->
            val fqName = record.fqName
            fqName == worldFqName || fqName.startsWith("$worldFqName.")
        }

        val bindingMetadata = nestedRecords.flatMap { record ->
            record.kmClass.collectBindingMetadata(record.fqName)
        }
        val resourceMetadata = nestedRecords.flatMap { record ->
            record.kmClass.collectResourceMetadata(record.fqName)
        }
        val constructorMetadata = nestedRecords.flatMap { record ->
            record.kmClass.collectConstructorMetadata(record.fqName)
        }

        return Preview2WorldMetadata(
            packageId = packageId,
            worldName = worldName,
            worldClassName = worldFqName,
            companionClassName = companionRecord?.fqName,
            driverClassName = driverRecord?.fqName,
            bindings = bindingMetadata,
            resources = resourceMetadata,
            constructors = constructorMetadata,
        )
    }

    private fun KmClass.collectBindingMetadata(ownerClassName: String): List<Preview2BindingMetadata> {
        val fromProperties = properties.mapNotNull { property ->
            val annotation = property.findAnnotation(WIT_BINDING_ANNOTATION) ?: return@mapNotNull null
            annotation.toBindingMetadata(
                ownerClassName = ownerClassName,
                declarationName = property.name,
                declarationKind = BindingDeclarationKind.PROPERTY,
            )
        }
        val fromFunctions = functions.mapNotNull { function ->
            val annotation = function.findAnnotation(WIT_BINDING_ANNOTATION) ?: return@mapNotNull null
            annotation.toBindingMetadata(
                ownerClassName = ownerClassName,
                declarationName = function.name,
                declarationKind = BindingDeclarationKind.FUNCTION,
            )
        }
        return fromProperties + fromFunctions
    }

    private fun KmClass.collectResourceMetadata(ownerClassName: String): List<Preview2ResourceMetadata> =
        properties.mapNotNull { property ->
            val annotation = property.findAnnotation(WIT_RESOURCE_ANNOTATION) ?: return@mapNotNull null
            Preview2ResourceMetadata(
                ownerClassName = ownerClassName,
                declarationName = property.name,
                interfaceName = annotation.stringArg("interfaceName") ?: "",
                resourceName = annotation.stringArg("resourceName") ?: "",
                ownHandleType = annotation.stringArg("ownHandleType"),
                borrowHandleType = annotation.stringArg("borrowHandleType"),
            )
        }

    private fun KmClass.collectConstructorMetadata(ownerClassName: String): List<Preview2ConstructorMetadata> {
        val fromFunctions = functions.mapNotNull { function ->
            val annotation = function.findAnnotation(WIT_CONSTRUCTOR_ANNOTATION) ?: return@mapNotNull null
            annotation.toConstructorMetadata(
                ownerClassName = ownerClassName,
                declarationName = function.name,
                declarationKind = ConstructorDeclarationKind.FUNCTION,
            )
        }
        val fromConstructors = constructors.mapNotNull { constructor ->
            val annotation = constructor.findAnnotation(WIT_CONSTRUCTOR_ANNOTATION) ?: return@mapNotNull null
            annotation.toConstructorMetadata(
                ownerClassName = ownerClassName,
                declarationName = "<init>",
                declarationKind = ConstructorDeclarationKind.CONSTRUCTOR,
            )
        }
        return fromFunctions + fromConstructors
    }

    private fun KmProperty.findAnnotation(expectedFqName: String): KmAnnotation? =
        annotations.firstOrNull { it.matches(expectedFqName) }

    private fun KmFunction.findAnnotation(expectedFqName: String): KmAnnotation? =
        annotations.firstOrNull { it.matches(expectedFqName) }

    private fun KmConstructor.findAnnotation(expectedFqName: String): KmAnnotation? =
        annotations.firstOrNull { it.matches(expectedFqName) }

    private fun KmClass.findAnnotation(expectedFqName: String): KmAnnotation? =
        annotations.firstOrNull { it.matches(expectedFqName) }

    private fun KmAnnotation.matches(expectedFqName: String): Boolean =
        className.replace('/', '.') == expectedFqName

    private fun KmAnnotation.stringArg(name: String): String? =
        (arguments[name] as? KmAnnotationArgument.StringValue)?.value

    private fun KmAnnotation.booleanArg(name: String): Boolean? =
        (arguments[name] as? KmAnnotationArgument.BooleanValue)?.value

    private fun KmAnnotation.enumArg(name: String): String? =
        (arguments[name] as? KmAnnotationArgument.EnumValue)?.enumEntryName

    private fun KmAnnotation.toBindingMetadata(
        ownerClassName: String,
        declarationName: String,
        declarationKind: BindingDeclarationKind,
    ): Preview2BindingMetadata {
        val direction = enumArg("direction")?.let(BindingDirection::valueOf)
            ?: error("@WitBinding missing direction")
        val kind = enumArg("kind")?.let(BindingKind::valueOf) ?: error("@WitBinding missing kind")
        return Preview2BindingMetadata(
            ownerClassName = ownerClassName,
            declarationName = declarationName,
            declarationKind = declarationKind,
            bindingName = stringArg("bindingName") ?: "",
            direction = direction,
            kind = kind,
            runtimeTarget = stringArg("runtimeTarget"),
            interfaceName = stringArg("interfaceName"),
            resourceName = stringArg("resourceName"),
            isAsync = booleanArg("isAsync") ?: false,
            usesStreams = booleanArg("usesStreams") ?: false,
        )
    }

    private fun KmAnnotation.toConstructorMetadata(
        ownerClassName: String,
        declarationName: String,
        declarationKind: ConstructorDeclarationKind,
    ): Preview2ConstructorMetadata {
        val direction = enumArg("direction")?.let(BindingDirection::valueOf)
            ?: error("@WitConstructor missing direction")
        return Preview2ConstructorMetadata(
            ownerClassName = ownerClassName,
            declarationName = declarationName,
            declarationKind = declarationKind,
            bindingName = stringArg("bindingName") ?: "",
            direction = direction,
        )
    }

    private data class ClassRecord(
        val fqName: String,
        val kmClass: KmClass,
    )
}

private fun <T> Enumeration<T>.asSequence(): Sequence<T> = sequence {
    while (hasMoreElements()) {
        yield(nextElement())
    }
}

data class Preview2ModuleMetadata(
    val moduleName: String,
    val worlds: List<Preview2WorldMetadata>,
)

data class Preview2WorldMetadata(
    val packageId: String,
    val worldName: String,
    val worldClassName: String,
    val companionClassName: String?,
    val driverClassName: String?,
    val bindings: List<Preview2BindingMetadata>,
    val resources: List<Preview2ResourceMetadata>,
    val constructors: List<Preview2ConstructorMetadata>,
)

data class Preview2BindingMetadata(
    val ownerClassName: String,
    val declarationName: String,
    val declarationKind: BindingDeclarationKind,
    val bindingName: String,
    val direction: BindingDirection,
    val kind: BindingKind,
    val runtimeTarget: String?,
    val interfaceName: String?,
    val resourceName: String?,
    val isAsync: Boolean,
    val usesStreams: Boolean,
)

data class Preview2ResourceMetadata(
    val ownerClassName: String,
    val declarationName: String,
    val interfaceName: String,
    val resourceName: String,
    val ownHandleType: String?,
    val borrowHandleType: String?,
)

data class Preview2ConstructorMetadata(
    val ownerClassName: String,
    val declarationName: String,
    val declarationKind: ConstructorDeclarationKind,
    val bindingName: String,
    val direction: BindingDirection,
)

enum class BindingDeclarationKind {
    PROPERTY,
    FUNCTION,
}

enum class ConstructorDeclarationKind {
    FUNCTION,
    CONSTRUCTOR,
}

enum class BindingDirection {
    IMPORT,
    EXPORT,
}

enum class BindingKind {
    FUNCTION,
    INTERFACE,
    RESOURCE,
}

public object Preview2MetadataDump {
    @JvmStatic
    fun main(args: Array<String>) {
        val outputPath = args.firstOrNull()
            ?: error("Expected output file path as the first argument")
        val repoRootProperty = System.getProperty("kotlin.repo.root")
        val repoRoot = repoRootProperty?.let { Path.of(it) } ?: Paths.get("").toAbsolutePath().normalize()
        val metadata = Preview2MetadataIntrospector.loadPreview2Metadata(repoRoot)
        val outputFile = Path.of(outputPath)
        outputFile.parent?.let { Files.createDirectories(it) }
        Files.writeString(outputFile, metadata.toPrettyJson() + "\n")
    }
}

fun Preview2ModuleMetadata.toPrettyJson(indentSize: Int = 2): String {
    val unit = " ".repeat(indentSize.coerceAtLeast(1))
    val rootIndent = ""
    val fieldIndent = rootIndent + unit
    val sb = StringBuilder()
    sb.append("{\n")
    sb.appendField(rootIndent, unit, "moduleName", jsonString(moduleName), isLast = false)
    sb.append(fieldIndent)
    sb.append("\"worlds\": ")
    if (worlds.isEmpty()) {
        sb.append("[]\n")
    } else {
        sb.append("[\n")
        worlds.forEachIndexed { index, world ->
            appendWorldJson(sb, fieldIndent + unit, unit, world)
            if (index != worlds.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append(fieldIndent).append("]\n")
    }
    sb.append("}")
    return sb.toString()
}

private fun appendWorldJson(
    builder: StringBuilder,
    indent: String,
    unit: String,
    world: Preview2WorldMetadata,
) {
    builder.append(indent).append("{\n")
    builder.appendField(indent, unit, "packageId", jsonString(world.packageId), isLast = false)
    builder.appendField(indent, unit, "worldName", jsonString(world.worldName), isLast = false)
    builder.appendField(indent, unit, "worldClass", jsonString(world.worldClassName), isLast = false)
    builder.appendField(indent, unit, "companionClass", world.companionClassName?.let(::jsonString) ?: "null", isLast = false)
    builder.appendField(indent, unit, "driverClass", world.driverClassName?.let(::jsonString) ?: "null", isLast = false)
    builder.appendArrayField(indent, unit, "bindings", world.bindings, isLast = false, ::appendBindingJson)
    builder.appendArrayField(indent, unit, "resources", world.resources, isLast = false, ::appendResourceJson)
    builder.appendArrayField(indent, unit, "constructors", world.constructors, isLast = true, ::appendConstructorJson)
    builder.append(indent).append("}")
}

private fun appendBindingJson(
    builder: StringBuilder,
    indent: String,
    unit: String,
    binding: Preview2BindingMetadata,
) {
    builder.append(indent).append("{\n")
    val fields = buildList<Pair<String, String>> {
        add("ownerClass" to jsonString(binding.ownerClassName))
        add("declarationName" to jsonString(binding.declarationName))
        add("declarationKind" to jsonString(binding.declarationKind.name))
        add("bindingName" to jsonString(binding.bindingName))
        add("direction" to jsonString(binding.direction.name))
        add("kind" to jsonString(binding.kind.name))
        add("runtimeTarget" to (binding.runtimeTarget?.let(::jsonString) ?: "null"))
        add("interfaceName" to (binding.interfaceName?.let(::jsonString) ?: "null"))
        add("resourceName" to (binding.resourceName?.let(::jsonString) ?: "null"))
        add("isAsync" to binding.isAsync.toString())
        add("usesStreams" to binding.usesStreams.toString())
    }
    fields.forEachIndexed { index, (name, value) ->
        builder.appendField(indent, unit, name, value, index == fields.lastIndex)
    }
    builder.append(indent).append("}")
}

private fun appendResourceJson(
    builder: StringBuilder,
    indent: String,
    unit: String,
    resource: Preview2ResourceMetadata,
) {
    builder.append(indent).append("{\n")
    val fields = buildList<Pair<String, String>> {
        add("ownerClass" to jsonString(resource.ownerClassName))
        add("declarationName" to jsonString(resource.declarationName))
        add("interfaceName" to jsonString(resource.interfaceName))
        add("resourceName" to jsonString(resource.resourceName))
        add("ownHandleType" to (resource.ownHandleType?.let(::jsonString) ?: "null"))
        add("borrowHandleType" to (resource.borrowHandleType?.let(::jsonString) ?: "null"))
    }
    fields.forEachIndexed { index, (name, value) ->
        builder.appendField(indent, unit, name, value, index == fields.lastIndex)
    }
    builder.append(indent).append("}")
}

private fun appendConstructorJson(
    builder: StringBuilder,
    indent: String,
    unit: String,
    constructor: Preview2ConstructorMetadata,
) {
    builder.append(indent).append("{\n")
    val fields = buildList<Pair<String, String>> {
        add("ownerClass" to jsonString(constructor.ownerClassName))
        add("declarationName" to jsonString(constructor.declarationName))
        add("declarationKind" to jsonString(constructor.declarationKind.name))
        add("bindingName" to jsonString(constructor.bindingName))
        add("direction" to jsonString(constructor.direction.name))
    }
    fields.forEachIndexed { index, (name, value) ->
        builder.appendField(indent, unit, name, value, index == fields.lastIndex)
    }
    builder.append(indent).append("}")
}

private fun StringBuilder.appendField(
    indent: String,
    unit: String,
    name: String,
    value: String,
    isLast: Boolean,
) {
    append(indent).append(unit)
    append('"').append(name).append("\": ").append(value)
    if (!isLast) append(',')
    append('\n')
}

private fun <T> StringBuilder.appendArrayField(
    indent: String,
    unit: String,
    name: String,
    values: List<T>,
    isLast: Boolean,
    elementWriter: (StringBuilder, String, String, T) -> Unit,
) {
    append(indent).append(unit)
    append('"').append(name).append("\": ")
    if (values.isEmpty()) {
        append("[]")
    } else {
        append("[\n")
        val elementIndent = indent + unit + unit
        values.forEachIndexed { index, value ->
            elementWriter(this, elementIndent, unit, value)
            if (index != values.lastIndex) append(',')
            append('\n')
        }
        append(indent).append(unit).append("]")
    }
    if (!isLast) append(',')
    append('\n')
}

private fun jsonString(value: String): String {
    val sb = StringBuilder(value.length + 16)
    sb.append('"')
    value.forEach { ch ->
        when (ch) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\b' -> sb.append("\\b")
            '\r' -> sb.append("\\r")
            '\n' -> sb.append("\\n")
            '\t' -> sb.append("\\t")
            '\u000C' -> sb.append("\\f")
            else -> if (ch < ' ') {
                sb.append("\\u")
                sb.append(ch.code.toString(16).padStart(4, '0'))
            } else {
                sb.append(ch)
            }
        }
    }
    sb.append('"')
    return sb.toString()
}
