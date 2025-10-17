/*
 * Component-only wasm stdlib: reflection helper without imports.
 */

package kotlin.wasm.internal

import kotlin.reflect.*

internal actual fun <T : Any> getKClassForObject(obj: Any): KClass<T> =
    KClassImpl(wasmGetObjectRtti(obj))

