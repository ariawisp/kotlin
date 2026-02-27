package org.jetbrains.kotlin.wit.codegen.core.plan

import org.jetbrains.kotlin.wit.model.BindingKind
import org.jetbrains.kotlin.wit.model.Stability
import org.jetbrains.kotlin.wit.model.WitBinding
import org.jetbrains.kotlin.wit.model.WitConstructor
import org.jetbrains.kotlin.wit.model.WitFunction
import org.jetbrains.kotlin.wit.model.WitSchema
import org.jetbrains.kotlin.wit.model.WitTopLevelUse
import org.jetbrains.kotlin.wit.model.WitUseName
import org.jetbrains.kotlin.wit.model.WitUsePath
import org.jetbrains.kotlin.wit.model.WitWorldInclude

public data class WitCodegenPlan(
    val packages: List<PackagePlan>,
    val features: Set<String>,
)

public data class PackagePlan(
    val id: String,
    val stability: Stability?,
    val includes: List<String>,
    val uses: List<WitTopLevelUse>,
    val worlds: List<WorldPlan>,
)

public data class WorldPlan(
    val name: String,
    val stability: Stability?,
    val imports: List<BindingPlan>,
    val exports: List<BindingPlan>,
    val constructors: List<ConstructorPlan>,
    val includes: List<WitWorldInclude>,
    val uses: List<ScopedUsePlan>,
)

public data class BindingPlan(
    val name: String,
    val kind: BindingKind,
    val target: BindingTargetPlan,
    val signature: WitFunction?,
)

public data class ConstructorPlan(
    val bindingName: String,
    val signature: WitFunction,
)

public data class ScopedUsePlan(
    val path: WitUsePath,
    val names: List<WitUseName>,
    val stability: Stability?,
)

public sealed interface BindingTargetPlan {
    public data class Interface(val name: String) : BindingTargetPlan
    public data class Resource(val name: String) : BindingTargetPlan
    public data class Function(val name: String) : BindingTargetPlan
}

internal fun WitSchema.packageIdString(): List<Pair<Int, String>> =
    packages.mapIndexed { index, pkg ->
        val id = buildString {
            append(pkg.id.namespace)
            append(":")
            append(pkg.id.name)
            if (!pkg.id.version.isNullOrBlank()) {
                append("@")
                append(pkg.id.version)
            }
        }
        index to id
    }

