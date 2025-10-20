/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.wasm.component

import kotlin.wasm.ExperimentalWasmInterop
import kotlin.wasm.WasmExport
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi

/**
 * Minimal exports required by the Wasm Component Model Canonical ABI.
 *
 * These functions provide allocation for lifted/lowered values and a hook to
 * free memory allocated during an exported function call.
 *
 * The names follow common defaults used by component adapters; hosts/tools may
 * be configured to use different names if needed.
 */
@OptIn(ExperimentalWasmInterop::class, UnsafeWasmMemoryApi::class)
@WasmExport("canonical_abi_realloc")
public fun canonicalAbiRealloc(
    originalPtr: Int,
    originalSize: Int,
    alignment: Int,
    newSize: Int,
): Int {
    // Alignment is currently guaranteed by the allocator; the parameter is accepted
    // to conform to the Canonical ABI signature.
    var out: Int = 0
    if (newSize != 0) {
        out = kotlin.wasm.unsafe.componentModelRealloc(originalPtr, originalSize, newSize)
    }
    return out
}

@OptIn(ExperimentalWasmInterop::class, UnsafeWasmMemoryApi::class)
@WasmExport("canonical_abi_post_return")
public fun canonicalAbiPostReturn() {
    kotlin.wasm.unsafe.freeAllComponentModelReallocAllocatedMemory()
}
