package org.jetbrains.kotlin.wit.compiler.fir

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal val WIT_RUNTIME_PACKAGE_FQNAME: FqName = FqName("org.jetbrains.kotlin.wit.runtime")
internal val WIT_WORLD_CLASS_ID: ClassId = ClassId(WIT_RUNTIME_PACKAGE_FQNAME, Name.identifier("WitWorld"))
internal val WIT_BINDING_CLASS_ID: ClassId = ClassId(WIT_RUNTIME_PACKAGE_FQNAME, Name.identifier("WitBinding"))
internal val WIT_BINDING_DIRECTION_CLASS_ID: ClassId =
    ClassId(WIT_RUNTIME_PACKAGE_FQNAME, Name.identifier("WitBindingDirection"))
internal val WIT_BINDING_KIND_CLASS_ID: ClassId =
    ClassId(WIT_RUNTIME_PACKAGE_FQNAME, Name.identifier("WitBindingKind"))
internal val WIT_RESOURCE_CLASS_ID: ClassId = ClassId(WIT_RUNTIME_PACKAGE_FQNAME, Name.identifier("WitResource"))
internal val WIT_CONSTRUCTOR_CLASS_ID: ClassId =
    ClassId(WIT_RUNTIME_PACKAGE_FQNAME, Name.identifier("WitConstructor"))
internal val WORLD_DRIVER_CLASS_ID: ClassId = ClassId(WIT_RUNTIME_PACKAGE_FQNAME, Name.identifier("WorldDriver"))
internal val COMPONENT_RUNTIME_CLASS_ID: ClassId =
    ClassId(WIT_RUNTIME_PACKAGE_FQNAME, Name.identifier("ComponentRuntime"))
internal val BINDING_HANDLER_CLASS_ID: ClassId =
    ClassId(WIT_RUNTIME_PACKAGE_FQNAME, Name.identifier("BindingHandler"))
internal val RESOURCE_FACTORY_CLASS_ID: ClassId =
    ClassId(WIT_RUNTIME_PACKAGE_FQNAME, Name.identifier("ResourceFactory"))
internal val RESOURCE_TYPE_CLASS_ID: ClassId =
    ClassId(WIT_RUNTIME_PACKAGE_FQNAME, Name.identifier("ResourceType"))
internal val OWN_HANDLE_CLASS_ID: ClassId =
    ClassId(WIT_RUNTIME_PACKAGE_FQNAME, Name.identifier("OwnHandle"))
internal val RESOURCE_CLASS_ID: ClassId = ClassId(WIT_RUNTIME_PACKAGE_FQNAME, Name.identifier("Resource"))
