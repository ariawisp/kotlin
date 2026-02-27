/*
 * Wasmtime environment definition for Kotlin Gradle Plugin.
 */
package org.jetbrains.kotlin.gradle.targets.wasm.wasmtime

import org.jetbrains.kotlin.gradle.targets.js.AbstractEnv
import java.io.File

data class WasmtimeEnv(
    override val download: Boolean,
    override val downloadBaseUrl: String?,
    override val allowInsecureProtocol: Boolean,
    override val ivyDependency: String,
    override val executable: String,
    override val dir: File,
) : AbstractEnv

