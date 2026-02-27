package org.jetbrains.kotlin.wit.compiler.ir

import org.jetbrains.kotlin.wit.codegen.core.plan.BindingPlan
import org.jetbrains.kotlin.wit.codegen.core.plan.BindingTargetPlan
import org.jetbrains.kotlin.wit.codegen.core.plan.ConstructorPlan
import org.jetbrains.kotlin.wit.codegen.core.plan.PackagePlan
import org.jetbrains.kotlin.wit.codegen.core.plan.ScopedUsePlan
import org.jetbrains.kotlin.wit.codegen.core.plan.WitCodegenPlan
import org.jetbrains.kotlin.wit.codegen.core.plan.WorldPlan
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeBinding
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeConstructor
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeFunction
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeSchema
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeType
import org.jetbrains.kotlin.wit.compiler.schema.WitRuntimeWorld
import org.jetbrains.kotlin.wit.model.BindingKind
import org.jetbrains.kotlin.wit.model.FuncKind
import org.jetbrains.kotlin.wit.model.Param
import org.jetbrains.kotlin.wit.model.TypeRef
import org.jetbrains.kotlin.wit.model.WitFunction
import org.jetbrains.kotlin.wit.model.WitTopLevelUse
import org.jetbrains.kotlin.wit.model.WitUseName
import org.jetbrains.kotlin.wit.model.WitUsePath
import org.jetbrains.kotlin.wit.model.WitWorldInclude

internal object WasmPlanBuilder {
    fun build(schema: WitRuntimeSchema): WitCodegenPlan {
        val packages = schema.packages.map { pkg ->
            println("[WIT] raw worlds for ${pkg.id}: " +
                pkg.worlds.joinToString { world -> "${world.name}(imports=${world.imports.size},exports=${world.exports.size})" })
            PackagePlan(
                id = pkg.id,
                stability = null,
                includes = pkg.includes.map { it.toString() },
                uses = emptyList<WitTopLevelUse>(),
                worlds = pkg.worlds.distinctBy { it.name }.map { world -> worldPlan(world) },
            )
        }
        val duplicatePackages = packages.groupBy { it.id }.filterValues { it.size > 1 }
        if (duplicatePackages.isNotEmpty()) {
            println("[WIT] duplicate packages detected: " +
                duplicatePackages.entries.joinToString { (id, list) -> "$id(count=${list.size})" })
        }
        println("[WIT] plan packages=" + packages.joinToString { pkg ->
            val worlds = pkg.worlds.joinToString(",") { it.name }
            "${pkg.id} worlds=${pkg.worlds.size} [$worlds]"
        })
        return WitCodegenPlan(packages = packages, features = schema.features)
    }

    private fun worldPlan(runtimeWorld: WitRuntimeWorld): WorldPlan {
        val imports = runtimeWorld.imports.map { it.toBindingPlan() }
        val exports = runtimeWorld.exports.map { it.toBindingPlan() }
        val constructors = runtimeWorld.constructors.map { constructorPlan(it) }
        return WorldPlan(
            name = runtimeWorld.name,
            stability = null,
            imports = imports,
            exports = exports,
            constructors = constructors,
            includes = emptyList<WitWorldInclude>(),
            uses = emptyList<ScopedUsePlan>(),
        )
    }

    private fun WitRuntimeBinding.toBindingPlan(): BindingPlan = BindingPlan(
        name = name,
        kind = mapBindingKind(bindingKind),
        target = when (bindingKind) {
            org.jetbrains.kotlin.wit.compiler.schema.BindingKind.FUNCTION -> BindingTargetPlan.Function(target)
            org.jetbrains.kotlin.wit.compiler.schema.BindingKind.INTERFACE -> BindingTargetPlan.Interface(target)
            org.jetbrains.kotlin.wit.compiler.schema.BindingKind.RESOURCE -> BindingTargetPlan.Resource(target)
        },
        signature = signature?.toModelFunction(),
    )

    private fun constructorPlan(constructor: WitRuntimeConstructor): ConstructorPlan = ConstructorPlan(
        bindingName = constructor.bindingName,
        signature = constructor.signature.toModelFunction(),
    )

    private fun WitRuntimeFunction.toModelFunction(): WitFunction = WitFunction(
        name = name,
        parameters = parameters.map { Param(it.label, typeRefFromString(it.typeRef)) },
        results = results.map { typeRefFromString(it.typeRef) },
        kind = mapFunctionKind(kind),
        isAsync = isAsync,
        usesStreams = usesStreams,
    )

    private fun mapBindingKind(kind: org.jetbrains.kotlin.wit.compiler.schema.BindingKind): BindingKind = when (kind) {
        org.jetbrains.kotlin.wit.compiler.schema.BindingKind.FUNCTION -> BindingKind.FUNCTION
        org.jetbrains.kotlin.wit.compiler.schema.BindingKind.INTERFACE -> BindingKind.INTERFACE
        org.jetbrains.kotlin.wit.compiler.schema.BindingKind.RESOURCE -> BindingKind.RESOURCE
    }

    private fun mapFunctionKind(kind: org.jetbrains.kotlin.wit.compiler.schema.FunctionKind): FuncKind = when (kind) {
        org.jetbrains.kotlin.wit.compiler.schema.FunctionKind.FUNCTION -> FuncKind.FUNCTION
        org.jetbrains.kotlin.wit.compiler.schema.FunctionKind.METHOD -> FuncKind.METHOD
        org.jetbrains.kotlin.wit.compiler.schema.FunctionKind.STATIC -> FuncKind.STATIC
        org.jetbrains.kotlin.wit.compiler.schema.FunctionKind.LIFT -> FuncKind.LIFT
        org.jetbrains.kotlin.wit.compiler.schema.FunctionKind.LOWER -> FuncKind.LOWER
        org.jetbrains.kotlin.wit.compiler.schema.FunctionKind.CONSTRUCTOR -> FuncKind.CONSTRUCTOR
    }

    private fun typeRefFromString(raw: String): TypeRef = when {
        raw.startsWith("own:") -> TypeRef.Handle(TypeRef.Handle.Ownership.OWN, raw.removePrefix("own:"))
        raw.startsWith("borrow:") -> TypeRef.Handle(TypeRef.Handle.Ownership.BORROW, raw.removePrefix("borrow:"))
        raw in PRIMITIVES -> TypeRef.Primitive(raw)
        else -> TypeRef.Identifier(raw)
    }

    private val PRIMITIVES = setOf(
        "bool",
        "string",
        "char",
        "u8", "u16", "u32", "u64",
        "s8", "s16", "s32", "s64",
        "f32", "f64",
    )
}
