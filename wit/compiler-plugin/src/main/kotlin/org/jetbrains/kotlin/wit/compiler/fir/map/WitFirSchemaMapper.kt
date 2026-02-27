package org.jetbrains.kotlin.wit.compiler.fir

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.wit.compiler.WIT_DRIVER_OBJECT_SIMPLE_NAME
import org.jetbrains.kotlin.wit.compiler.schema.BindingKind
import org.jetbrains.kotlin.wit.compiler.schema.WitInterfaceMetadata
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeBinding
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeConstructor
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeInterface
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeResource
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeWorld
import org.jetbrains.kotlin.wit.compiler.schema.WitSchemaIndex
import org.jetbrains.kotlin.wit.compiler.schema.WitWorldMetadata

internal class WitFirSchemaMapper(private val schemaIndex: WitSchemaIndex) {

    fun map(): SchemaMapping {
        val declarations = mutableMapOf<ClassId, WorldMetadata>()
        val packages = mutableSetOf<FqName>()
        val packageMetadataByName = schemaIndex.packageMetadata.associateBy { it.name }

        schemaIndex.runtimeSchema.packages.forEach { runtimePackage ->
            val packageFqName = packageToFqName(runtimePackage.id)
            packages += packageFqName

            val schemaPackage = runtimePackage.metadata ?: packageMetadataByName[runtimePackage.id]
            val runtimeInterfacesByName = runtimePackage.interfaces.associateBy { it.name }
            val schemaInterfacesByName = schemaPackage?.interfaces?.associateBy { it.name }.orEmpty()

            val interfaceBindings = runtimeInterfacesByName.map { (name, runtimeInterface) ->
                interfacePropertyName(name) to InterfaceBindingMetadata(
                    runtime = runtimeInterface,
                    schema = schemaInterfacesByName[name],
                )
            }.toMap()

            val functionInterfaceIndex = buildMap {
                runtimeInterfacesByName.values.forEach { runtimeInterface ->
                    runtimeInterface.functions.forEach { function ->
                        put(function.name, runtimeInterface.name)
                    }
                }
            }

            runtimePackage.worlds.forEach { runtimeWorld ->
                val worldSchema = schemaPackage?.worlds?.firstOrNull { it.name == runtimeWorld.name }
                val classId = ClassId(packageFqName, Name.identifier(sanitizeIdentifier(runtimeWorld.name)))

                val importHostNames = mutableSetOf<String>()
                val importBindings = runtimeWorld.imports.associate { binding ->
                    val propertyName = bindingPropertyName("Import", binding.name)
                    propertyName to createBindingMetadata(
                        binding = binding,
                        direction = BindingDirection.IMPORT,
                        functionInterfaceIndex = functionInterfaceIndex,
                        hostNames = importHostNames,
                    )
                }

                val exportHostNames = mutableSetOf<String>()
                val exportBindings = runtimeWorld.exports.associate { binding ->
                    val propertyName = bindingPropertyName("Export", binding.name)
                    propertyName to createBindingMetadata(
                        binding = binding,
                        direction = BindingDirection.EXPORT,
                        functionInterfaceIndex = functionInterfaceIndex,
                        hostNames = exportHostNames,
                    )
                }

                val resourceHostNames = mutableSetOf<String>()
                val resourceBindings = runtimeInterfacesByName.values.flatMap { runtimeInterface ->
                    runtimeInterface.resources.map { resource ->
                        val hostBase = listOf("resource", runtimeInterface.name, resource.name)
                            .joinToString(separator = "_") { it.trim().ifEmpty { "resource" } }
                        val hostFunction = Name.identifier(allocateHostFunctionName(hostBase, resourceHostNames))
                        resourcePropertyName(runtimeInterface.name, resource.name) to ResourceBindingMetadata(
                            runtimeInterface = runtimeInterface,
                            resource = resource,
                            hostFunctionName = hostFunction,
                        )
                    }
                }.toMap()

                val companionClassId = classId.createNestedClassId(SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT)
                val driverClassId = companionClassId.createNestedClassId(Name.identifier(WIT_DRIVER_OBJECT_SIMPLE_NAME))
                val companionRelativeClassName =
                    classId.relativeClassName.child(SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT)
                val bindCallableId = CallableId(classId.packageFqName, companionRelativeClassName, BIND_FUNCTION_NAME)
                val hasImportHostFunctions = importBindings.values.any { it.hostFunctionName != null }
                val hasExportHostFunctions = exportBindings.values.any { it.hostFunctionName != null }
                val driverImportsClassId = if (hasImportHostFunctions) {
                    driverClassId.createNestedClassId(IMPORTS_INTERFACE_NAME)
                } else {
                    null
                }
                val driverExportsClassId = if (hasExportHostFunctions) {
                    driverClassId.createNestedClassId(EXPORTS_INTERFACE_NAME)
                } else {
                    null
                }
                val driverResourcesClassId = if (resourceBindings.isNotEmpty()) {
                    driverClassId.createNestedClassId(RESOURCES_INTERFACE_NAME)
                } else {
                    null
                }
                val registerImportsCallableId = if (hasImportHostFunctions) {
                    CallableId(classId.packageFqName, companionRelativeClassName, REGISTER_IMPORTS_FUNCTION_NAME)
                } else {
                    null
                }
                val registerExportsCallableId = if (hasExportHostFunctions) {
                    CallableId(classId.packageFqName, companionRelativeClassName, REGISTER_EXPORTS_FUNCTION_NAME)
                } else {
                    null
                }
                val registerResourcesCallableId = if (resourceBindings.isNotEmpty()) {
                    CallableId(classId.packageFqName, companionRelativeClassName, REGISTER_RESOURCES_FUNCTION_NAME)
                } else {
                    null
                }

                val constructorHelpers = buildConstructorHelpers(
                    runtimeWorld,
                    importBindings,
                    exportBindings,
                )

                val metadataEntry = WorldMetadata(
                    packageId = runtimePackage.id,
                    packageFqName = packageFqName,
                    runtimeWorld = runtimeWorld,
                    worldSchema = worldSchema,
                    interfaceBindings = interfaceBindings,
                    importBindings = importBindings,
                    exportBindings = exportBindings,
                    resourceBindings = resourceBindings,
                    constructorHelpers = constructorHelpers,
                    driverCompanionClassId = companionClassId,
                    driverClassId = driverClassId,
                    bindCallableId = bindCallableId,
                    driverImportsClassId = driverImportsClassId,
                    driverExportsClassId = driverExportsClassId,
                    registerImportsCallableId = registerImportsCallableId,
                    registerExportsCallableId = registerExportsCallableId,
                    driverResourcesClassId = driverResourcesClassId,
                    registerResourcesCallableId = registerResourcesCallableId,
                )
                declarations[classId] = metadataEntry
                declarations[companionClassId] = metadataEntry
                declarations[driverClassId] = metadataEntry
                driverImportsClassId?.let { declarations[it] = metadataEntry }
                driverExportsClassId?.let { declarations[it] = metadataEntry }
                driverResourcesClassId?.let { declarations[it] = metadataEntry }
            }
        }

        val topLevelClassIds = declarations.keys.filterTo(mutableSetOf()) { it.outerClassId == null }
        return SchemaMapping(
            worldDeclarations = declarations,
            packages = packages,
            topLevelClassIds = topLevelClassIds,
        )
    }

    private fun createBindingMetadata(
        binding: WitRuntimeBinding,
        direction: BindingDirection,
        functionInterfaceIndex: Map<String, String>,
        hostNames: MutableSet<String>,
    ): BindingMetadata {
        val stubName = functionStubName(binding, direction)
        val owner = resolveBindingOwner(binding, functionInterfaceIndex)
        val signature = binding.signature
        val hostFunctionName = if (stubName != null && signature != null) {
            val baseName = computeHostFunctionBaseName(binding, owner.interfaceName, owner.resourceName)
            val allocated = allocateHostFunctionName(baseName, hostNames)
            Name.identifier(allocated)
        } else {
            null
        }
        return BindingMetadata(
            binding = binding,
            direction = direction,
            functionStubName = stubName,
            hostFunctionName = hostFunctionName,
            interfaceName = owner.interfaceName,
            resourceName = owner.resourceName,
        )
    }

    private fun buildConstructorHelpers(
        runtimeWorld: WitRuntimeWorld,
        importBindings: Map<Name, BindingMetadata>,
        exportBindings: Map<Name, BindingMetadata>,
    ): Map<Name, ConstructorHelperMetadata> {
        if (runtimeWorld.constructors.isEmpty()) return emptyMap()
        val helpers = linkedMapOf<Name, ConstructorHelperMetadata>()
        val usedNames = mutableSetOf<String>()
        runtimeWorld.constructors.forEach { constructor ->
            val importBinding = importBindings.values.firstOrNull { it.binding.name == constructor.bindingName }
                ?.let { it to BindingDirection.IMPORT }
            val exportBinding = exportBindings.values.firstOrNull { it.binding.name == constructor.bindingName }
                ?.let { it to BindingDirection.EXPORT }
            val (bindingMetadata, direction) = importBinding ?: exportBinding ?: return@forEach
            if (direction != BindingDirection.EXPORT) return@forEach
            val helperName = allocateConstructorHelperName(bindingMetadata.resourceName ?: constructor.bindingName, usedNames)
            helpers[helperName] = ConstructorHelperMetadata(
                functionName = helperName,
                constructor = constructor,
                binding = bindingMetadata,
                direction = direction,
            )
        }
        return helpers
    }

    private fun resolveBindingOwner(
        binding: WitRuntimeBinding,
        functionInterfaceIndex: Map<String, String>,
    ): BindingOwner {
        return when (binding.bindingKind) {
            BindingKind.INTERFACE -> BindingOwner(
                interfaceName = binding.target.takeIf { it.isNotBlank() },
                resourceName = null,
            )
            BindingKind.RESOURCE -> BindingOwner(
                interfaceName = null,
                resourceName = binding.target.takeIf { it.isNotBlank() },
            )
            BindingKind.FUNCTION -> {
                val signature = binding.signature
                val interfaceName = signature?.name?.let { functionInterfaceIndex[it] }
                val resourceName = signature?.let { parseWitFunctionName(it.name).scope }
                BindingOwner(
                    interfaceName = interfaceName,
                    resourceName = resourceName,
                )
            }
        }
    }

    private fun packageToFqName(packageId: String): FqName {
        val withoutVersion = packageId.substringBefore('@')
        val segments = withoutVersion.replace(':', '.').split('.')
        val sanitized = buildList {
            add("wit")
            add("generated")
            segments.filter { it.isNotBlank() }.forEach { add(sanitizeIdentifier(it)) }
        }
        return FqName(sanitized.joinToString(separator = "."))
    }

    private data class BindingOwner(
        val interfaceName: String?,
        val resourceName: String?,
    )
}

internal data class SchemaMapping(
    val worldDeclarations: Map<ClassId, WorldMetadata>,
    val packages: Set<FqName>,
    val topLevelClassIds: Set<ClassId>,
)
