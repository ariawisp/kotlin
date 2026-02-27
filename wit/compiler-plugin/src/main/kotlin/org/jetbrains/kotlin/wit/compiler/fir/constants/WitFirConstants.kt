package org.jetbrains.kotlin.wit.compiler.fir

import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.wit.compiler.WIT_DRIVER_BIND_FUNCTION_NAME
import org.jetbrains.kotlin.wit.compiler.WIT_DRIVER_OBJECT_SIMPLE_NAME

internal val WORLD_NAME_PROPERTY_NAME: Name = Name.identifier("__witWorldName")
internal val RUNTIME_SLOT_PROPERTY_NAME: Name = Name.identifier("__witRuntime")
internal val DRIVER_OBJECT_NAME: Name = Name.identifier(WIT_DRIVER_OBJECT_SIMPLE_NAME)
internal val DRIVER_PACKAGE_ID_PROPERTY_NAME: Name = Name.identifier("packageId")
internal val DRIVER_WORLD_NAME_PROPERTY_NAME: Name = Name.identifier("worldName")
internal val BIND_FUNCTION_NAME: Name = Name.identifier(WIT_DRIVER_BIND_FUNCTION_NAME)
internal val REGISTER_IMPORTS_FUNCTION_NAME: Name = Name.identifier("registerImports")
internal val REGISTER_EXPORTS_FUNCTION_NAME: Name = Name.identifier("registerExports")
internal val REGISTER_RESOURCES_FUNCTION_NAME: Name = Name.identifier("registerResources")
internal val REGISTER_IMPORT_HANDLER_NAME: Name = Name.identifier("registerImportHandler")
internal val REGISTER_EXPORT_HANDLER_NAME: Name = Name.identifier("registerExportHandler")
internal val RUNTIME_PARAMETER_NAME: Name = Name.identifier("runtime")
internal val IMPLEMENTATION_PARAMETER_NAME: Name = Name.identifier("impl")
internal val IMPORTS_INTERFACE_NAME: Name = Name.identifier("Imports")
internal val EXPORTS_INTERFACE_NAME: Name = Name.identifier("Exports")
internal val RESOURCES_INTERFACE_NAME: Name = Name.identifier("Resources")
