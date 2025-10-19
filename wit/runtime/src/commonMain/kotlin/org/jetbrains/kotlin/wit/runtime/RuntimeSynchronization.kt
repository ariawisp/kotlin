package org.jetbrains.kotlin.wit.runtime

internal expect inline fun <R> runtimeSynchronized(lock: Any, block: () -> R): R
