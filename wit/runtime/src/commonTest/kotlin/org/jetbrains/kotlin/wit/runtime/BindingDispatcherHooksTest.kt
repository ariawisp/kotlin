package org.jetbrains.kotlin.wit.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BindingDispatcherHooksTest {
    @Test
    fun hooksObserveRegistration() {
        val events = mutableListOf<String>()
        val hooks = object : BindingDispatcherHooks {
            override fun onRegisterImport(packageId: String, worldName: String, bindingName: String, handler: BindingHandler) {
                events += "import:$packageId/$worldName#$bindingName"
            }

            override fun onRegisterExport(packageId: String, worldName: String, bindingName: String, handler: BindingHandler) {
                events += "export:$packageId/$worldName#$bindingName"
            }
        }

        val dispatcher = DefaultBindingDispatcher(hooks)
        dispatcher.registerImport("pkg", "world", "call") { _ -> null }
        dispatcher.registerExport("pkg", "world", "send") { _ -> null }

        assertEquals(listOf(
            "import:pkg/world#call",
            "export:pkg/world#send",
        ), events)
    }

    @Test
    fun hooksObserveDispatchLifecycle() {
        val starts = mutableListOf<BindingDelegate>()
        val ends = mutableListOf<Pair<BindingDelegate, Any?>>()
        val argsSeen = mutableListOf<List<Any?>>()

        val hooks = object : BindingDispatcherHooks {
            override fun onDispatchStart(delegate: BindingDelegate, arguments: Array<out Any?>) {
                starts += delegate
                argsSeen += arguments.toList()
            }

            override fun onDispatchEnd(delegate: BindingDelegate, arguments: Array<out Any?>, result: Any?) {
                ends += delegate to result
                argsSeen += arguments.toList()
            }
        }

        val dispatcher = DefaultBindingDispatcher(hooks)
        val delegate = pendingBindingDelegate(
            packageId = "pkg",
            worldName = "world",
            bindingName = "call",
            direction = WitBindingDirection.IMPORT,
            kind = WitBindingKind.FUNCTION,
            runtimeTarget = "",
            isAsync = false,
            usesStreams = false,
        )
        dispatcher.registerImport("pkg", "world", "call") { args ->
            assertEquals(listOf(1, "two"), args.toList())
            "ok"
        }

        val result = dispatcher.dispatch(delegate, arrayOf<Any?>(1, "two"))
        assertEquals("ok", result)

        // Hooks saw both start and end with the same delegate reference
        assertEquals(1, starts.size)
        assertEquals(1, ends.size)
        assertSame(starts.single(), ends.single().first)
        assertEquals("ok", ends.single().second)
        // Arguments are observed at start and end
        val expected: List<List<Any?>> = listOf(listOf(1, "two"), listOf(1, "two"))
        assertEquals(expected, argsSeen)
    }

    @Test
    fun missingHandlerErrorMessageIsConsistent() {
        val dispatcher = DefaultBindingDispatcher()
        val delegate = pendingBindingDelegate(
            packageId = "pkg",
            worldName = "world",
            bindingName = "send",
            direction = WitBindingDirection.EXPORT,
            kind = WitBindingKind.FUNCTION,
            runtimeTarget = "",
            isAsync = false,
            usesStreams = false,
        )
        val error = kotlin.runCatching { dispatcher.dispatch(delegate, emptyArray()) }.exceptionOrNull()
        val message = error?.message ?: ""
        assertTrue(message.contains("pkg/world"))
        assertTrue(message.contains("send"))
        assertTrue(message.contains("EXPORT"))
        assertTrue(message.contains("registerExports"))
    }
}
