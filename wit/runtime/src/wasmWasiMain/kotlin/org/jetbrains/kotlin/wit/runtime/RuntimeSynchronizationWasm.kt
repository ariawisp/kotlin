package org.jetbrains.kotlin.wit.runtime

internal actual inline fun <R> runtimeSynchronized(lock: Any, block: () -> R): R = block()
