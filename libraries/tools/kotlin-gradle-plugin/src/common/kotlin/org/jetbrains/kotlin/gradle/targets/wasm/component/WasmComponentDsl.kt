/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.wasm.component

import org.gradle.api.Action
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.model.ObjectFactory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinWasmTargetDsl
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import javax.inject.Inject

/**
 * Stable Gradle DSL to configure the Wasm Component Model pipeline for Kotlin/Wasm targets.
 *
 * Example:
 *  kotlin {
 *    wasmWasi {
 *      component {
 *        name.convention(project.name)
 *        witDir.set(layout.projectDirectory.dir("src/main/wit"))
 *        world.set("my:pkg/world")
 *        importMemory.convention(false)
 *      }
 *    }
 *  }
 */
abstract class WasmComponentOptions @Inject constructor(objects: ObjectFactory) {
    /** Name of the resulting component. Defaults to project name if unset. */
    val name: Property<String> = objects.property(String::class.java)

    /** Optional: WIT directory root. Mutually exclusive with [witFile]. */
    val witDir: DirectoryProperty = objects.directoryProperty()

    /** Optional: Single WIT file. Mutually exclusive with [witDir]. */
    val witFile: RegularFileProperty = objects.fileProperty()

    /** Optional: WIT world name to use when assembling the component. */
    val world: Property<String> = objects.property(String::class.java)

    /** Whether to import linear memory instead of defining it. Defaults to false. */
    val importMemory: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
}

private const val EXTENSION_NAME = "component"

/**
 * Configure Wasm Component Model options on a Wasm target.
 */
fun KotlinWasmTargetDsl.component(configure: Action<WasmComponentOptions>) {
    val extAware = this as ExtensionAware
    val existing = extAware.extensions.findByType(WasmComponentOptions::class.java)
    val ext = existing ?: extAware.extensions.create(EXTENSION_NAME, WasmComponentOptions::class.java)
    // Default the component name to project name if not set
    val kt = this as KotlinTarget
    ext.name.convention(kt.project.name)
    configure.execute(ext)
}
