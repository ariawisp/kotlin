package org.jetbrains.kotlin.wit.codegen.core.plan

import org.jetbrains.kotlin.wit.model.BindingKind
import org.jetbrains.kotlin.wit.model.BindingTarget
import org.jetbrains.kotlin.wit.model.WitSchema

public object WitCodegenPlanner {
    public fun build(schema: WitSchema): WitCodegenPlan {
        val packages = schema.packages.map { pkg ->
            val id = buildString {
                append(pkg.id.namespace)
                append(":")
                append(pkg.id.name)
                if (!pkg.id.version.isNullOrBlank()) {
                    append("@")
                    append(pkg.id.version)
                }
            }

            val worlds = pkg.worlds.map { world ->
                val imports = world.imports.map { it.toPlan() }
                val exports = world.exports.map { it.toPlan() }
                val constructors = world.constructors
                    .distinctBy { it.bindingName }
                    .map { ConstructorPlan(it.bindingName, it.signature) }
                val includes = world.includes
                val uses = world.uses.map { ScopedUsePlan(it.path, it.names, it.stability) }

                WorldPlan(
                    name = world.name,
                    stability = world.stability,
                    imports = imports,
                    exports = exports,
                    constructors = constructors,
                    includes = includes,
                    uses = uses,
                )
            }

            PackagePlan(
                id = id,
                stability = pkg.stability,
                includes = pkg.includes.sorted(),
                uses = pkg.uses,
                worlds = worlds,
            )
        }

        return WitCodegenPlan(packages = packages, features = schema.features)
    }

    private fun org.jetbrains.kotlin.wit.model.WitBinding.toPlan(): BindingPlan = BindingPlan(
        name = name,
        kind = kind,
        target = when (val t = target) {
            is BindingTarget.Interface -> BindingTargetPlan.Interface(t.name)
            is BindingTarget.Resource -> BindingTargetPlan.Resource(t.name)
            is BindingTarget.Function -> BindingTargetPlan.Function(t.name)
        },
        signature = signature,
    )
}

