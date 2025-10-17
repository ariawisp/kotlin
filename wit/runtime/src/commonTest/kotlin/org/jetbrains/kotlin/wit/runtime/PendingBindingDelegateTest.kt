package org.jetbrains.kotlin.wit.runtime

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PendingBindingDelegateTest {
    @Test
    fun exposesMetadata() {
        val delegate = pendingBindingDelegate(
            packageId = "pkg.demo",
            worldName = "world",
            bindingName = "demoBinding",
            direction = WitBindingDirection.IMPORT,
            kind = WitBindingKind.FUNCTION,
            runtimeTarget = "demoTarget",
            isAsync = true,
            usesStreams = false,
        )

        assertEquals("demoBinding", delegate.bindingName)
        assertEquals(WitBindingDirection.IMPORT, delegate.direction)
        assertEquals(WitBindingKind.FUNCTION, delegate.kind)
        assertEquals("demoTarget", delegate.runtimeTarget)
        assertTrue(delegate.isAsync)
        assertFalse(delegate.usesStreams)

        val attachError = assertFailsWith<IllegalStateException> {
            delegate.attach(createDefaultRuntime())
        }
        assertTrue(attachError.message?.contains("registerImports") == true)

        val rendered = delegate.toString()
        assertTrue(rendered.contains("demoBinding"))
        assertTrue(rendered.contains("direction=IMPORT"))
        assertTrue(rendered.contains("kind=FUNCTION"))
        assertTrue(rendered.contains("target=demoTarget"))
        assertTrue(rendered.contains("async=true"))
        assertFalse(rendered.contains("streams=true"))
    }

    @Test
    fun attachIsDeprecated() {
        val delegate = pendingBindingDelegate(
            packageId = "pkg",
            worldName = "world",
            bindingName = "demo",
            direction = WitBindingDirection.IMPORT,
            kind = WitBindingKind.FUNCTION,
            runtimeTarget = "",
            isAsync = false,
            usesStreams = false,
        )

        val runtime = createDefaultRuntime()
        val error = assertFailsWith<IllegalStateException> {
            delegate.attach(runtime)
        }
        assertTrue(error.message?.contains("registerImports") == true)
    }

    @Test
    fun dispatchBindingThrows() {
        val delegate = pendingBindingDelegate(
            packageId = "pkg",
            worldName = "world",
            bindingName = "demo",
            direction = WitBindingDirection.EXPORT,
            kind = WitBindingKind.FUNCTION,
            runtimeTarget = "target",
            isAsync = false,
            usesStreams = true,
        )

        val error = assertFailsWith<IllegalStateException> {
            dispatchBinding(delegate, 1, "foo")
        }
        assertTrue(error.message?.contains("demo") == true)
        assertTrue(error.message?.contains("streams") == true)
    }

    @Test
    fun runtimeDispatchUsesDispatcher() {
        val delegate = pendingBindingDelegate(
            packageId = "pkg",
            worldName = "world",
            bindingName = "call",
            direction = WitBindingDirection.IMPORT,
            kind = WitBindingKind.FUNCTION,
            runtimeTarget = "",
            isAsync = false,
            usesStreams = false,
            signature = BindingSignature(listOf(BindingTypeRef(typeRef = "type")), emptyList()),
        )

        val captured = mutableListOf<List<Any?>>()
        val dispatcher = DefaultBindingDispatcher().apply {
            registerImport("pkg", "world", "call") {
                captured += it.toList()
                "ok"
            }
        }

        val runtime = DefaultComponentRuntime(dispatcher = dispatcher)
        val result = runtime.dispatchBinding(delegate, 42, "hello")

        assertEquals(listOf(42, "hello"), captured.single())
        assertEquals("ok", result)
    }

    @Test
    fun runtimeDispatchMarshalsValues() {
        val delegate = pendingBindingDelegate(
            packageId = "pkg",
            worldName = "world",
            bindingName = "call",
            direction = WitBindingDirection.IMPORT,
            kind = WitBindingKind.FUNCTION,
            runtimeTarget = "",
            isAsync = false,
            usesStreams = false,
            signature = BindingSignature(listOf(BindingTypeRef(typeRef = "u32", label = "value")), emptyList()),
        )

        val encodedArguments = arrayOf<Any?>(999)
        val dispatcherArguments = mutableListOf<Array<out Any?>>()
        val dispatcher = object : BindingDispatcher {
            override fun registerImport(
                packageId: String,
                worldName: String,
                bindingName: String,
                handler: BindingHandler,
            ) {
                // no-op
            }

            override fun registerExport(
                packageId: String,
                worldName: String,
                bindingName: String,
                handler: BindingHandler,
            ) {
                // no-op
            }

            override fun dispatch(delegate: BindingDelegate, arguments: Array<out Any?>): Any? {
                dispatcherArguments.add(arguments)
                return "encoded-result"
            }
        }

        val marshaller = object : BindingValueMarshaller {
            override fun encodeArguments(
                signature: BindingSignature,
                arguments: Array<out Any?>,
            ): Array<Any?> {
                return encodedArguments
            }

            override fun decodeArguments(
                signature: BindingSignature,
                arguments: Array<out Any?>,
            ): Array<Any?> = Array(arguments.size) { index -> arguments[index] }

            override fun decodeResult(signature: BindingSignature, result: Any?): Any? {
                return "decoded-$result"
            }

            override fun encodeResult(signature: BindingSignature, result: Any?): Any? {
                return result
            }
        }

        val runtime = DefaultComponentRuntime(dispatcher = dispatcher, marshaller = marshaller)
        val result = runtime.dispatchBinding(delegate, 123)

        assertEquals(1, dispatcherArguments.size)
        assertSame(encodedArguments, dispatcherArguments.single())
        assertEquals("decoded-encoded-result", result)
    }

    @Test
    fun runtimeRejectsAsyncBindingsForNow() {
        val delegate = pendingBindingDelegate(
            packageId = "pkg",
            worldName = "world",
            bindingName = "asyncCall",
            direction = WitBindingDirection.IMPORT,
            kind = WitBindingKind.FUNCTION,
            runtimeTarget = "",
            isAsync = true,
            usesStreams = false,
        )

        val runtime = DefaultComponentRuntime()
        val result = runSuspendCatching { runtime.dispatchAsyncBinding(delegate) }
        val unsupported = result.exceptionOrNull() as? UnsupportedOperationException
        assertNotNull(unsupported)
        assertTrue(unsupported.message?.contains("async") == true)
        assertTrue(unsupported.message?.contains("asyncCall") == true)
    }

    @Test
    fun syncDispatchRejectsAsyncBindings() {
        val delegate = pendingBindingDelegate(
            packageId = "pkg",
            worldName = "world",
            bindingName = "asyncSyncCall",
            direction = WitBindingDirection.EXPORT,
            kind = WitBindingKind.FUNCTION,
            runtimeTarget = "",
            isAsync = true,
            usesStreams = false,
        )

        val runtime = DefaultComponentRuntime()
        val error = assertFailsWith<UnsupportedOperationException> {
            runtime.dispatchBinding(delegate)
        }
        assertTrue(error.message?.contains("asyncSyncCall") == true)
    }

    @Test
    fun runtimeRejectsStreamingBindingsForNow() {
        val delegate = pendingBindingDelegate(
            packageId = "pkg",
            worldName = "world",
            bindingName = "streamCall",
            direction = WitBindingDirection.EXPORT,
            kind = WitBindingKind.FUNCTION,
            runtimeTarget = "",
            isAsync = false,
            usesStreams = true,
        )

        val runtime = DefaultComponentRuntime()
        val error = assertFailsWith<UnsupportedOperationException> {
            runtime.openBindingStream<Any?>(delegate)
        }
        assertTrue(error.message?.contains("stream") == true)
        assertTrue(error.message?.contains("streamCall") == true)
    }

    @Test
    fun syncDispatchRejectsStreamingBindings() {
        val delegate = pendingBindingDelegate(
            packageId = "pkg",
            worldName = "world",
            bindingName = "streamSyncCall",
            direction = WitBindingDirection.IMPORT,
            kind = WitBindingKind.FUNCTION,
            runtimeTarget = "",
            isAsync = false,
            usesStreams = true,
        )

        val runtime = DefaultComponentRuntime()
        val error = assertFailsWith<UnsupportedOperationException> {
            runtime.dispatchBinding(delegate)
        }
        assertTrue(error.message?.contains("streamSyncCall") == true)
    }

    @Test
    fun delegateCarriesSignatureMetadata() {
        val signature = BindingSignature(
            parameters = listOf(BindingTypeRef(typeRef = "u32", label = "value")),
            results = listOf(BindingTypeRef(typeRef = "string")),
        )
        val delegate = pendingBindingDelegate(
            packageId = "pkg",
            worldName = "world",
            bindingName = "demo",
            direction = WitBindingDirection.EXPORT,
            kind = WitBindingKind.FUNCTION,
            runtimeTarget = "",
            isAsync = false,
            usesStreams = false,
            signature = signature,
        )

        assertEquals(signature, delegate.signature)
        assertEquals(listOf(BindingTypeRef(typeRef = "u32", label = "value")), delegate.signature.parameters)
        assertEquals(listOf(BindingTypeRef(typeRef = "string")), delegate.signature.results)
    }

    @Test
    fun hostCanInstallImportHandler() {
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

        val runtime = DefaultComponentRuntime()
        val captured = mutableListOf<List<Any?>>()
        runtime.registerImportHandler("pkg", "world", "call") {
            captured += it.toList()
            "ok"
        }

        val result = runtime.dispatchBinding(delegate, 7, "hi")
        assertEquals(listOf(7, "hi"), captured.single())
        assertEquals("ok", result)
    }

    @Test
    fun hostCanInstallExportHandler() {
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

        val runtime = DefaultComponentRuntime()
        var invoked = false
        runtime.registerExportHandler("pkg", "world", "send") {
            invoked = true
            null
        }

        runtime.dispatchBinding(delegate)
        assertTrue(invoked)
    }

    @Test
    fun pendingBindingHandlerProducesHandler() {
        val handler = pendingBindingHandler(
            packageId = "pkg",
            worldName = "world",
            bindingName = "call",
            direction = WitBindingDirection.IMPORT,
            kind = WitBindingKind.FUNCTION,
            runtimeTarget = "targetFn",
            isAsync = true,
            usesStreams = true,
        )

        val error = assertFailsWith<IllegalStateException> {
            handler(emptyArray())
        }
        val message = error.message ?: ""
        assertTrue(message.contains("pkg/world"))
        assertTrue(message.contains("call"))
        assertTrue(message.contains("IMPORT"))
        assertTrue(message.contains("async"))
        assertTrue(message.contains("streams"))
        assertTrue(message.contains("targetFn"))
        assertTrue(message.contains("registerImports") || message.contains("registerExports"))
    }

    @Test
    fun driverHelpersBridgeToRuntime() {
        val runtime = DefaultComponentRuntime()
        val importsDelegate = pendingBindingDelegate(
            packageId = SAMPLE_PACKAGE,
            worldName = SAMPLE_WORLD,
            bindingName = "call",
            direction = WitBindingDirection.IMPORT,
            kind = WitBindingKind.FUNCTION,
            runtimeTarget = "",
            isAsync = false,
            usesStreams = false,
        )

        val missingImport = assertFailsWith<IllegalStateException> {
            runtime.dispatchBinding(importsDelegate, 1, "two")
        }
        assertTrue(missingImport.message?.contains("registerImports") == true)

        val importCaptured = mutableListOf<List<Any?>>()
        registerStubImports(runtime, object : StubImports {
            override fun call(value: Int, text: String): Any? {
                importCaptured += listOf(value, text)
                return "import-result"
            }
        })
        val importResult = runtime.dispatchBinding(importsDelegate, 1, "two")
        assertEquals(listOf(listOf<Any?>(1, "two")), importCaptured)
        assertEquals("import-result", importResult)

        val exportsDelegate = pendingBindingDelegate(
            packageId = SAMPLE_PACKAGE,
            worldName = SAMPLE_WORLD,
            bindingName = "send",
            direction = WitBindingDirection.EXPORT,
            kind = WitBindingKind.FUNCTION,
            runtimeTarget = "",
            isAsync = false,
            usesStreams = false,
        )

        val missingExport = assertFailsWith<IllegalStateException> {
            runtime.dispatchBinding(exportsDelegate, 99)
        }
        assertTrue(missingExport.message?.contains("registerExports") == true)

        val exportCaptured = mutableListOf<Int>()
        registerStubExports(runtime, object : StubExports {
            override fun send(value: Int) {
                exportCaptured += value
            }
        })
        runtime.dispatchBinding(exportsDelegate, 99)
        assertEquals(listOf(99), exportCaptured)

        registerStubResources(runtime, object : StubResources {
            override fun widget(): ResourceFactory = ResourceFactory { StubResourceAdapter }
        })
        val resourceFactory = runtime.resolveResource(ResourceType(SAMPLE_PACKAGE, SAMPLE_INTERFACE, SAMPLE_RESOURCE))
        val adapter = resourceFactory?.create(runtime)
        assertNotNull(adapter)
        assertEquals(SAMPLE_RESOURCE, adapter.type.resourceName)
    }

    private interface StubImports {
        fun call(value: Int, text: String): Any?
    }

    private interface StubExports {
        fun send(value: Int)
    }

    private interface StubResources {
        fun widget(): ResourceFactory
    }

    private fun registerStubImports(runtime: ComponentRuntime, impl: StubImports) {
        runtime.registerImportHandler(SAMPLE_PACKAGE, SAMPLE_WORLD, "call") { args ->
            impl.call(args[0] as Int, args[1] as String)
        }
    }

    private fun registerStubExports(runtime: ComponentRuntime, impl: StubExports) {
        runtime.registerExportHandler(SAMPLE_PACKAGE, SAMPLE_WORLD, "send") { args ->
            impl.send((args.getOrNull(0) as? Number)?.toInt() ?: 0)
            null
        }
    }

    private fun registerStubResources(runtime: ComponentRuntime, impl: StubResources) {
        runtime.registerResource(
            ResourceType(SAMPLE_PACKAGE, SAMPLE_INTERFACE, SAMPLE_RESOURCE),
            impl.widget(),
        )
    }

    private fun runSuspendCatching(block: suspend () -> Any?): Result<Any?> {
        var outcome: Result<Any?>? = null
        block.startCoroutine(object : Continuation<Any?> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Any?>) {
                outcome = result
            }
        })
        return outcome ?: error("Suspension did not resume")
    }

    private companion object {
        private const val SAMPLE_PACKAGE = "pkg"
        private const val SAMPLE_WORLD = "world"
        private const val SAMPLE_INTERFACE = "sample-interface"
        private const val SAMPLE_RESOURCE = "widget"
        private object StubResourceAdapter : ResourceAdapter {
            override val type: ResourceType = ResourceType(SAMPLE_PACKAGE, SAMPLE_INTERFACE, SAMPLE_RESOURCE)
            override fun ownHandlePrototype(): Handle<Resource>? = null
            override fun borrowHandlePrototype(): Handle<Resource>? = null
        }
    }
}
