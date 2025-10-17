
@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package org.jetbrains.kotlin.wit.compiler.ir

import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.wit.compiler.WitPluginOptions
import org.jetbrains.kotlin.wit.runtime.WitBindingDirection
import org.jetbrains.kotlin.wit.runtime.WitBindingKind
import java.nio.file.Path

class WitIrDriverRegistrationLoweringTest {
    private val fixture by lazy { compileFixture() }
    private val multiFixture by lazy { compileMultiFixture() }

    @Test
    fun `register imports emits pending handler`() {
        val context = fixture
        val world = context.plan.worlds.single()
        assertTrue(world.bindings.isNotEmpty(), "Plan missing bindings: ${context.plan.render()}")
        assertTrue(world.driver != null, "Plan missing driver: ${context.plan.render()}")
        WitIrBindingBodyLowering(context.pluginContext, context.plan).apply()
        WitIrDriverRegistrationLowering(context.pluginContext, context.options, context.plan).apply()

        val loweredBody = context.registerImports.dumpKotlinLike()

        assertTrue(
            loweredBody.contains("registerImportHandler") &&
                loweredBody.contains("pendingBindingHandler") &&
                loweredBody.contains("packageId = \"$PACKAGE_ID\"") &&
                loweredBody.contains("worldName = \"$WORLD_NAME\"") &&
                loweredBody.contains("$IMPORTED_BINDING"),
            "Expected registerImports to install pending handler when no host impl is available:\n$loweredBody",
        )
    }

    @Test
    fun `multi-world register imports isolate runtime keys`() {
        val context = multiFixture
        assertTrue(context.plan.worlds.size == 2, "Expected two worlds in multi-world plan: ${context.plan.render()}")

        WitIrBindingBodyLowering(context.pluginContext, context.plan).apply()
        WitIrDriverRegistrationLowering(context.pluginContext, context.options, context.plan).apply()

        val loweredBodies = context.registerImports.map { it.dumpKotlinLike() }
        val primaryBody = loweredBodies[0]
        val secondaryBody = loweredBodies[1]

        assertTrue(
            primaryBody.contains("packageId = \"$PACKAGE_ID\"") &&
                primaryBody.contains("worldName = \"$WORLD_NAME\"") &&
                !primaryBody.contains("worldName = \"$WORLD_NAME_ALT\""),
            "Primary world should reference its own runtime identifiers:\n$primaryBody",
        )
        assertTrue(
            secondaryBody.contains("packageId = \"$PACKAGE_ID_ALT\"") &&
                secondaryBody.contains("worldName = \"$WORLD_NAME_ALT\"") &&
                !secondaryBody.contains("worldName = \"$WORLD_NAME\""),
            "Secondary world should reference its own runtime identifiers:\n$secondaryBody",
        )

        loweredBodies.forEach { body ->
            assertTrue(
                body.countSubstring("registerImportHandler") == 1,
                "Each world should emit exactly one pending handler registration:\n$body",
            )
        }

        val resourceBodies = context.registerResources.map { it.dumpKotlinLike() }
        resourceBodies.forEach { body ->
            assertTrue(
                body.countSubstring("registerResource(") == 1,
                "Duplicate resources should be coalesced per world:\n$body",
            )
        }
    }

    private fun compileFixture(): Fixture {
        val compilation = TestIrCompiler.compile(KOTLIN_STUB, RUNTIME_STUB, WORLD_STUB)
        val module = compilation.moduleFragment
        val worldClass = module.findClass("test.FixtureWorld") ?: error("FixtureWorld not found")
        val companion = worldClass.innerClasses().single { it.isCompanion }
        val runtimeProperty = companion.findProperty(RUNTIME_SLOT_NAME) ?: error("__witRuntime not found")
        val importedDelegate = worldClass.findProperty("importedDelegate") ?: error("importedDelegate not found")
        val importedFunction = worldClass.findFunction("importedFunction")

        val driverClass = companion.innerClasses().firstOrNull { it.name.asString() == DRIVER_CLASS_NAME }
            ?: error("Driver class not found")
        val bindFunction = companion.findFunction(DRIVER_BIND_FUNCTION_NAME)
        val registerImportHandler = companion.findFunction(DRIVER_REGISTER_IMPORT_HANDLER)
        val registerImports = companion.findFunction(DRIVER_REGISTER_IMPORTS)

        registerImports.body = null
        bindFunction.body = null

        val world = WitIrPlan.World(
            irClass = worldClass,
            packageId = PACKAGE_ID,
            worldName = WORLD_NAME,
            bindings = listOf(
                createDelegateBinding(importedDelegate, IMPORTED_BINDING),
                createFunctionBinding(importedFunction, IMPORTED_BINDING, WitBindingDirection.IMPORT),
            ),
            resources = emptyList(),
            constructors = emptyList(),
            driver = WitIrPlan.Driver(
                companion = companion,
                driverClass = driverClass,
                bindFunction = bindFunction,
                registerImportHandler = registerImportHandler,
                registerExportHandler = null,
                registerImports = registerImports,
                registerExports = null,
                registerResources = null,
                importsContract = null,
                exportsContract = null,
                resourcesContract = null,
            ),
            runtimeSlot = WitIrPlan.RuntimeSlot(
                property = runtimeProperty,
                backingField = runtimeProperty.backingField ?: error("Runtime property lacks backing field"),
            ),
        )

        return Fixture(
            pluginContext = compilation.pluginContext,
            options = DEFAULT_OPTIONS,
            plan = WitIrPlan(listOf(world)),
            registerImports = registerImports,
        )
    }

    private fun compileMultiFixture(): MultiFixture {
        val compilation = TestIrCompiler.compile(KOTLIN_STUB, RUNTIME_STUB, WORLD_STUB, MIRROR_WORLD_STUB)
        val module = compilation.moduleFragment

        fun buildWorldPlan(
            classFqName: String,
            packageId: String,
            worldName: String,
        ): Triple<WitIrPlan.World, IrSimpleFunction, IrSimpleFunction> {
            val worldClass = module.findClass(classFqName) ?: error("World class $classFqName not found")
            val companion = worldClass.innerClasses().single { it.isCompanion }
            val runtimeProperty = companion.findProperty(RUNTIME_SLOT_NAME) ?: error("__witRuntime not found in $classFqName")
            val importedDelegate = worldClass.findProperty("importedDelegate") ?: error("importedDelegate not found in $classFqName")
            val importedFunction = worldClass.findFunction("importedFunction")
            val driverClass = companion.innerClasses().firstOrNull { it.name.asString() == DRIVER_CLASS_NAME }
                ?: error("Driver class not found in $classFqName")
            val bindFunction = companion.findFunction(DRIVER_BIND_FUNCTION_NAME)
            val registerImportHandler = companion.findFunction(DRIVER_REGISTER_IMPORT_HANDLER)
            val registerImports = companion.findFunction(DRIVER_REGISTER_IMPORTS)
            val registerResources = companion.findFunction(DRIVER_REGISTER_RESOURCES)

            val driverResourcesInterface = driverClass.innerClasses().firstOrNull { it.name.asString() == RESOURCES_INTERFACE_NAME }
            val resourcesContract = driverResourcesInterface?.let { resourcesClass ->
                val factoryFunction = resourcesClass.findFunction(SHARED_RESOURCE_FACTORY_NAME)
                WitIrPlan.ResourcesContract(
                    irClass = resourcesClass,
                    bindings = mapOf(
                        resourceKey(INTERFACE_NAME, SHARED_RESOURCE_NAME) to WitIrPlan.ResourcesContract.ResourceBinding(
                            function = factoryFunction,
                            interfaceName = INTERFACE_NAME,
                            resourceName = SHARED_RESOURCE_NAME,
                        ),
                    ),
                )
            }

            registerImports.body = null
            bindFunction.body = null
            registerResources.body = null

            val sharedResource = createResource(importedDelegate, INTERFACE_NAME, SHARED_RESOURCE_NAME)

            val worldPlan = WitIrPlan.World(
                irClass = worldClass,
                packageId = packageId,
                worldName = worldName,
                bindings = listOf(
                    createDelegateBinding(importedDelegate, IMPORTED_BINDING),
                    createFunctionBinding(importedFunction, IMPORTED_BINDING, WitBindingDirection.IMPORT),
                ),
                resources = listOf(sharedResource, createResource(importedDelegate, INTERFACE_NAME, SHARED_RESOURCE_NAME)),
                constructors = emptyList(),
                driver = WitIrPlan.Driver(
                    companion = companion,
                    driverClass = driverClass,
                    bindFunction = bindFunction,
                    registerImportHandler = registerImportHandler,
                    registerExportHandler = null,
                    registerImports = registerImports,
                    registerExports = null,
                    registerResources = registerResources,
                    importsContract = null,
                    exportsContract = null,
                    resourcesContract = resourcesContract,
                ),
                runtimeSlot = WitIrPlan.RuntimeSlot(
                    property = runtimeProperty,
                    backingField = runtimeProperty.backingField ?: error("Runtime property lacks backing field in $classFqName"),
                ),
            )

            return Triple(worldPlan, registerImports, registerResources)
        }

        val (primaryWorld, primaryRegisterImports, primaryRegisterResources) = buildWorldPlan("test.FixtureWorld", PACKAGE_ID, WORLD_NAME)
        val (secondaryWorld, secondaryRegisterImports, secondaryRegisterResources) = buildWorldPlan("test.MirrorWorld", PACKAGE_ID_ALT, WORLD_NAME_ALT)

        return MultiFixture(
            pluginContext = compilation.pluginContext,
            options = DEFAULT_OPTIONS,
            plan = WitIrPlan(listOf(primaryWorld, secondaryWorld)),
            registerImports = listOf(primaryRegisterImports, secondaryRegisterImports),
            registerResources = listOf(primaryRegisterResources, secondaryRegisterResources),
        )
    }

    private data class Fixture(
        val pluginContext: IrPluginContext,
        val options: WitPluginOptions,
        val plan: WitIrPlan,
        val registerImports: IrSimpleFunction,
    )

    private data class MultiFixture(
        val pluginContext: IrPluginContext,
        val options: WitPluginOptions,
        val plan: WitIrPlan,
        val registerImports: List<IrSimpleFunction>,
        val registerResources: List<IrSimpleFunction>,
    )

    private fun createFunctionBinding(
        declaration: IrDeclaration,
        bindingName: String,
        direction: WitBindingDirection,
    ): WitIrPlan.Binding = WitIrPlan.Binding(
        declaration = declaration,
        declarationName = declaration.renderName(),
        direction = direction,
        kind = WitBindingKind.FUNCTION,
        interfaceName = INTERFACE_NAME,
        resourceName = "",
        bindingName = bindingName,
        runtimeTarget = "",
        isAsync = false,
        usesStreams = false,
        parameterTypes = emptyList(),
        resultTypes = emptyList(),
    )

    private fun createDelegateBinding(
        declaration: IrProperty,
        bindingName: String,
    ): WitIrPlan.Binding = WitIrPlan.Binding(
        declaration = declaration,
        declarationName = declaration.name.asString(),
        direction = WitBindingDirection.IMPORT,
        kind = WitBindingKind.FUNCTION,
        interfaceName = INTERFACE_NAME,
        resourceName = "",
        bindingName = bindingName,
        runtimeTarget = "",
        isAsync = false,
        usesStreams = false,
        parameterTypes = emptyList(),
        resultTypes = emptyList(),
    )

    private fun createResource(
        declaration: IrDeclaration,
        interfaceName: String,
        resourceName: String,
    ): WitIrPlan.Resource = WitIrPlan.Resource(
        declaration = declaration,
        declarationName = declaration.renderName(),
        interfaceName = interfaceName,
        resourceName = resourceName,
        ownHandleType = "own:$interfaceName/$resourceName",
        borrowHandleType = "borrow:$interfaceName/$resourceName",
    )

    private fun resourceKey(interfaceName: String, resourceName: String): String =
        "$interfaceName/$resourceName"

    private fun IrClass.innerClasses(): List<IrClass> = declarations.filterIsInstance<IrClass>()

    private fun IrClass.findProperty(name: String): IrProperty? =
        declarations.filterIsInstance<IrProperty>().firstOrNull { it.name.asString() == name }

    private fun IrClass.findFunction(name: String): IrSimpleFunction =
        declarations.filterIsInstance<IrSimpleFunction>().firstOrNull { it.name.asString() == name }
            ?: error("Function $name not found in ${'$'}{this.name.asString()}")

    private fun IrDeclaration.renderName(): String = when (this) {
        is IrProperty -> name.asString()
        is IrSimpleFunction -> name.asString()
        else -> toString()
    }

    private fun org.jetbrains.kotlin.ir.declarations.IrModuleFragment.findClass(fqName: String): IrClass? {
        val target = FqName(fqName)
        files.forEach { file ->
            file.declarations.filterIsInstance<IrClass>().forEach { klass ->
                if (klass.fqNameWhenAvailable == target) return klass
            }
        }
        return null
    }

    companion object {
        private const val PACKAGE_ID = "test:pkg"
        private const val WORLD_NAME = "FixtureWorld"
        private const val PACKAGE_ID_ALT = "test:pkg.mirror"
        private const val WORLD_NAME_ALT = "MirrorWorld"
        private const val INTERFACE_NAME = "fixtures"
        private const val IMPORTED_BINDING = "[function]imported"
        private const val RUNTIME_SLOT_NAME = "__witRuntime"
        private const val DRIVER_CLASS_NAME = "__WitDriver"
        private const val DRIVER_BIND_FUNCTION_NAME = "bind"
        private const val DRIVER_REGISTER_IMPORT_HANDLER = "registerImportHandler"
        private const val DRIVER_REGISTER_IMPORTS = "registerImports"
        private const val DRIVER_REGISTER_RESOURCES = "registerResources"
        private const val RESOURCES_INTERFACE_NAME = "Resources"
        private const val SHARED_RESOURCE_NAME = "resource"
        private const val SHARED_RESOURCE_FACTORY_NAME = "sharedResource"

        private val DEFAULT_OPTIONS = WitPluginOptions(
            enabled = true,
            debug = false,
            rootPaths = emptyList<Path>(),
            includePaths = emptyList<Path>(),
            features = emptySet<String>(),
            jsonSchemas = emptyList<Path>(),
        )

        private val KOTLIN_STUB = TestSourceFile(
            name = "kotlin/UnsupportedOperationException.kt",
            contents = """
            package kotlin
            class UnsupportedOperationException(message: String) : RuntimeException(message)
            """.trimIndent(),
        )

        private val RUNTIME_STUB = TestSourceFile(
            name = "RuntimeStub.kt",
            contents = """
            package org.jetbrains.kotlin.wit.runtime
            enum class WitBindingDirection { IMPORT, EXPORT }
            enum class WitBindingKind { FUNCTION, RESOURCE, INTERFACE }
            interface BindingDelegate {
                val packageId: String
                val worldName: String
                val bindingName: String
                val direction: WitBindingDirection
                val kind: WitBindingKind
                val runtimeTarget: String
                val isAsync: Boolean
                val usesStreams: Boolean
                val signature: BindingSignature
            }

            data class BindingSignature(
                val parameters: List<BindingTypeRef>,
                val results: List<BindingTypeRef>,
            ) {
                companion object {
                    val EMPTY = BindingSignature(emptyList(), emptyList())
                }
            }

            data class BindingTypeRef(
                val typeRef: String,
                val label: String = "",
                val shape: BindingValueShape = BindingValueShape.Unknown,
            )

            sealed class BindingValueShape {
                object Unknown : BindingValueShape()
                data class Scalar(val name: String) : BindingValueShape()
                data class ResourceHandle(val ownership: ResourceHandleOwnership) : BindingValueShape()
            }

            enum class ResourceHandleOwnership { OWNED, BORROWED }

            interface BindingValueMarshaller {
                fun decodeArguments(signature: BindingSignature, arguments: Array<out Any?>): Array<Any?>
                fun encodeResult(signature: BindingSignature, result: Any?): Any?
            }

            typealias BindingHandler = (Array<out Any?>) -> Any?

            interface Resource
            class ResourceType(val packageName: String, val interfaceName: String, val resourceName: String)
            interface ResourceAdapter : Resource
            fun interface ResourceFactory {
                fun create(runtime: ComponentRuntime): ResourceAdapter
            }

            interface ComponentRuntime {
                val marshaller: BindingValueMarshaller
                fun registerDriver(driver: Any) {}
                fun registerImportHandler(packageId: String, worldName: String, bindingName: String, handler: BindingHandler) {}
                fun registerExportHandler(packageId: String, worldName: String, bindingName: String, handler: BindingHandler) {}
                fun registerResource(type: ResourceType, factory: ResourceFactory): ResourceFactory? = null
                fun registerResourceFactory(
                    type: ResourceType,
                    factory: ResourceFactory,
                    constructor: (ComponentRuntime, ResourceFactory, Array<out Any?>) -> Resource,
                ) {}
                fun dispatchBinding(delegate: BindingDelegate, vararg arguments: Any?): Any? = null
            }

            fun pendingBindingHandler(
                packageId: String,
                worldName: String,
                bindingName: String,
                direction: WitBindingDirection,
                kind: WitBindingKind,
                runtimeTarget: String,
                isAsync: Boolean,
                usesStreams: Boolean,
            ): BindingHandler = { _ -> error("stub handler") }

            fun pendingBindingDelegate(
                packageId: String,
                worldName: String,
                bindingName: String,
                direction: WitBindingDirection,
                kind: WitBindingKind,
                runtimeTarget: String,
                isAsync: Boolean,
                usesStreams: Boolean,
                signature: BindingSignature,
            ): BindingDelegate = object : BindingDelegate {
                override val packageId: String = packageId
                override val worldName: String = worldName
                override val bindingName: String = bindingName
                override val direction: WitBindingDirection = direction
                override val kind: WitBindingKind = kind
                override val runtimeTarget: String = runtimeTarget
                override val isAsync: Boolean = isAsync
                override val usesStreams: Boolean = usesStreams
                override val signature: BindingSignature = signature
            }
            """.trimIndent(),
        )

        private val WORLD_STUB = TestSourceFile(
            name = "FixtureWorld.kt",
            contents = """
            package test
            import org.jetbrains.kotlin.wit.runtime.*

            abstract class FixtureWorld {
                abstract val importedDelegate: BindingDelegate
                abstract fun importedFunction(value: Int): Any

                companion object {
                    var __witRuntime: ComponentRuntime? = null

                    fun bind(runtime: ComponentRuntime) {}
                    fun registerImportHandler(runtime: ComponentRuntime, binding: String, handler: BindingHandler) {}
                    fun registerExportHandler(runtime: ComponentRuntime, binding: String, handler: BindingHandler) {}
                    fun registerImports(runtime: ComponentRuntime, host: __WitDriver.Imports) {}
                    fun registerExports(runtime: ComponentRuntime, host: Any) {}
                    fun registerResources(runtime: ComponentRuntime, host: __WitDriver.Resources) {}

                    class __WitDriver {
                        interface Imports {
                            fun importedFunction(value: Int): Any
                        }

                        interface Resources {
                            fun sharedResource(): ResourceFactory
                        }
                    }
                }
            }
            """.trimIndent(),
        )

        private val MIRROR_WORLD_STUB = TestSourceFile(
            name = "MirrorWorld.kt",
            contents = """
            package test
            import org.jetbrains.kotlin.wit.runtime.*

            abstract class MirrorWorld {
                abstract val importedDelegate: BindingDelegate
                abstract fun importedFunction(value: Int): Any

                companion object {
                    var __witRuntime: ComponentRuntime? = null

                    fun bind(runtime: ComponentRuntime) {}
                    fun registerImportHandler(runtime: ComponentRuntime, binding: String, handler: BindingHandler) {}
                    fun registerExportHandler(runtime: ComponentRuntime, binding: String, handler: BindingHandler) {}
                    fun registerImports(runtime: ComponentRuntime, host: __WitDriver.Imports) {}
                    fun registerExports(runtime: ComponentRuntime, host: Any) {}
                    fun registerResources(runtime: ComponentRuntime, host: __WitDriver.Resources) {}

                    class __WitDriver {
                        interface Imports {
                            fun importedFunction(value: Int): Any
                        }

                        interface Resources {
                            fun sharedResource(): ResourceFactory
                        }
                    }
                }
            }
            """.trimIndent(),
        )
    }
}

private fun String.countSubstring(fragment: String): Int {
    if (fragment.isEmpty()) return 0
    var count = 0
    var startIndex = 0
    while (true) {
        val index = indexOf(fragment, startIndex)
        if (index < 0) break
        count++
        startIndex = index + fragment.length
    }
    return count
}
