@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package org.jetbrains.kotlin.wit.compiler.ir

import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.wit.runtime.WitBindingDirection
import org.jetbrains.kotlin.wit.runtime.WitBindingKind

class WitIrBindingBodyLoweringTest {
    private val fixture by lazy { compileFixture() }

    @Test
    fun `owning constructor receives runtime plumbing`() {
        val context = fixture
        val pluginContext = context.compilation.pluginContext
        val plan = context.plan

        WitIrBindingBodyLowering(pluginContext, plan).apply()

        val owningFunction = context.owningFunction
        val owningDump = owningFunction.dumpKotlinLike()

        assertTrue(
            owningDump.contains("EQEQ(arg0 = tmp0_runtime, arg1 = null)") &&
                owningDump.contains("error(message = \"WIT runtime not bound: $PACKAGE_ID/$WORLD_NAME -> $OWNED_BINDING (export, resource, bind(runtime), registerExports)\")"),
            "Expected runtime null guard in lowered body:\n$owningDump",
        )
        assertTrue(
            owningDump.contains("resolveResource(type = ResourceType(packageName = \"$PACKAGE_ID\", interfaceName = \"$INTERFACE_NAME\", resourceName = \"$OWNED_RESOURCE\"))"),
            "Expected resource lookup in lowered body:\n$owningDump",
        )
        assertTrue(
            owningDump.contains("Companion.owningHelper(runtime = tmp0_runtime, factory = tmp1_factory as ResourceFactory, flag = flag)"),
            "Expected helper invocation in lowered body:\n$owningDump",
        )
    }

    @Test
    fun `borrowed constructor throws unsupported`() {
        val context = fixture
        val pluginContext = context.compilation.pluginContext
        val plan = context.plan

        WitIrBindingBodyLowering(pluginContext, plan).apply()

        val borrowedDump = context.borrowedFunction.dumpKotlinLike()
        val expectedMessage =
            "Borrowed resource constructors are not supported yet: $PACKAGE_ID/$WORLD_NAME -> $BORROWED_BINDING (resource=$INTERFACE_NAME/$BORROWED_RESOURCE)"

        assertTrue(
            borrowedDump.contains("UnsupportedOperationException(message = \"$expectedMessage\")"),
            "Expected borrowed constructor to throw:\n$borrowedDump",
        )
    }

    @Test
    fun `exported function dispatches via runtime`() {
        val context = fixture
        val pluginContext = context.compilation.pluginContext
        val plan = context.plan

        WitIrBindingBodyLowering(pluginContext, plan).apply()

        val functionDump = context.exportedFunction.dumpKotlinLike()

        assertTrue(
            functionDump.contains("EQEQ(arg0 = tmp0_runtime, arg1 = null)"),
            "Expected runtime guard in exported function:\n$functionDump",
        )
        assertTrue(
            functionDump.contains("<this>.<get-exportedDelegate>()"),
            "Expected delegate lookup in exported function:\n$functionDump",
        )
        assertTrue(
            functionDump.contains("tmp0_runtime.dispatchBinding(delegate = <this>.<get-exportedDelegate>(), arguments = [value, flag])"),
            "Expected dispatchBinding call in exported function:\n$functionDump",
        )
    }

    @Test
    fun `imported function dispatches via runtime`() {
        val context = fixture
        val pluginContext = context.compilation.pluginContext
        val plan = context.plan

        WitIrBindingBodyLowering(pluginContext, plan).apply()

        val functionDump = context.importedFunction.dumpKotlinLike()

        assertTrue(
            functionDump.contains("EQEQ(arg0 = tmp0_runtime, arg1 = null)"),
            "Expected runtime guard in imported function:\n$functionDump",
        )
        assertTrue(
            functionDump.contains("<this>.<get-importedDelegate>()"),
            "Expected delegate lookup in imported function:\n$functionDump",
        )
        assertTrue(
            functionDump.contains("tmp0_runtime.dispatchBinding(delegate = <this>.<get-importedDelegate>(), arguments = [value])"),
            "Expected dispatchBinding call in imported function:\n$functionDump",
        )
        assertTrue(
            functionDump.contains("registerImports"),
            "Expected runtime missing hint to mention registerImports:\n$functionDump",
        )
    }

    @Test
    fun `resource method dispatches via runtime`() {
        val context = fixture
        val pluginContext = context.compilation.pluginContext
        val plan = context.plan

        WitIrBindingBodyLowering(pluginContext, plan).apply()

        val methodDump = context.resourceMethod.dumpKotlinLike()

        assertTrue(
            methodDump.contains("EQEQ(arg0 = tmp0_runtime, arg1 = null)"),
            "Expected runtime guard in resource method:\n$methodDump",
        )
        assertTrue(
            methodDump.contains("<this>.<get-methodDelegate>()"),
            "Expected delegate lookup in resource method:\n$methodDump",
        )
        assertTrue(
            methodDump.contains("tmp0_runtime.dispatchBinding(delegate = <this>.<get-methodDelegate>(), arguments = [value, flag])"),
            "Expected dispatchBinding call in resource method:\n$methodDump",
        )
    }

    private fun compileFixture(): FixtureContext {
        val compilation = TestIrCompiler.compile(KOTLIN_STUB, RUNTIME_STUB, WORLD_STUB)
        val worldClass = compilation.moduleFragment.findClass("test.FixtureWorld")
            ?: error("FixtureWorld class not found in compiled module")
        val companion = worldClass.innerClasses().single { it.isCompanion }
        val runtimeProperty = companion.findProperty(RUNTIME_SLOT_NAME)
            ?: error("Runtime slot property not found")
        val owningProperty = worldClass.findProperty("owningDelegate")
            ?: error("owningDelegate property not found")
        val borrowedProperty = worldClass.findProperty("borrowedDelegate")
            ?: error("borrowedDelegate property not found")
        val exportedProperty = worldClass.findProperty("exportedDelegate")
            ?: error("exportedDelegate property not found")
        val importedProperty = worldClass.findProperty("importedDelegate")
            ?: error("importedDelegate property not found")
        val owningFunction = worldClass.findFunction("owningConstructor")
        val borrowedFunction = worldClass.findFunction("borrowedConstructor")
        val exportedFunction = worldClass.findFunction("exportedFunction")
        val importedFunction = worldClass.findFunction("importedFunction")
        val owningHelper = companion.findFunction("owningHelper")
        val borrowedHelper = companion.findFunction("borrowedHelper")
        val ownedResourceClass = worldClass.innerClasses().firstOrNull { it.name.asString() == "OwnedResource" }
            ?: error("OwnedResource class not found")
        val resourceMethodProperty = ownedResourceClass.findProperty("methodDelegate")
            ?: error("methodDelegate property not found on OwnedResource")
        val resourceMethod = ownedResourceClass.findFunction("method")

        val plan = createPlan(
            worldClass = worldClass,
            runtimeProperty = runtimeProperty,
            owningProperty = owningProperty,
            borrowedProperty = borrowedProperty,
            exportedProperty = exportedProperty,
            importedProperty = importedProperty,
            owningFunction = owningFunction,
            borrowedFunction = borrowedFunction,
            exportedFunction = exportedFunction,
            importedFunction = importedFunction,
            owningHelper = owningHelper,
            borrowedHelper = borrowedHelper,
            resourceMethodProperty = resourceMethodProperty,
            resourceMethod = resourceMethod,
        )

        return FixtureContext(
            compilation = compilation,
            plan = plan,
            owningFunction = owningFunction,
            borrowedFunction = borrowedFunction,
            exportedFunction = exportedFunction,
            importedFunction = importedFunction,
            resourceMethod = resourceMethod,
        )
    }

    private fun createPlan(
        worldClass: IrClass,
        runtimeProperty: IrProperty,
        owningProperty: IrProperty,
        borrowedProperty: IrProperty,
        exportedProperty: IrProperty,
        importedProperty: IrProperty,
        owningFunction: IrSimpleFunction,
        borrowedFunction: IrSimpleFunction,
        exportedFunction: IrSimpleFunction,
        importedFunction: IrSimpleFunction,
        owningHelper: IrSimpleFunction,
        borrowedHelper: IrSimpleFunction,
        resourceMethodProperty: IrProperty,
        resourceMethod: IrSimpleFunction,
    ): WitIrPlan {
        val owningBinding = createResourceBinding(
            declaration = owningProperty,
            bindingName = OWNED_BINDING,
            interfaceName = INTERFACE_NAME,
            resourceName = OWNED_RESOURCE,
        )
        val borrowedBinding = createResourceBinding(
            declaration = borrowedProperty,
            bindingName = BORROWED_BINDING,
            interfaceName = INTERFACE_NAME,
            resourceName = BORROWED_RESOURCE,
        )
        val owningFunctionBinding = createResourceBinding(
            declaration = owningFunction,
            bindingName = OWNED_BINDING,
            interfaceName = INTERFACE_NAME,
            resourceName = OWNED_RESOURCE,
            resultShape = WitTypeShape.ResourceHandle(WitTypeShape.ResourceHandle.Ownership.OWNED),
        )
        val borrowedFunctionBinding = createResourceBinding(
            declaration = borrowedFunction,
            bindingName = BORROWED_BINDING,
            interfaceName = INTERFACE_NAME,
            resourceName = BORROWED_RESOURCE,
            resultShape = WitTypeShape.ResourceHandle(WitTypeShape.ResourceHandle.Ownership.BORROWED),
        )
        val exportedDelegateBinding = createFunctionBinding(
            declaration = exportedProperty,
            bindingName = FUNCTION_BINDING,
            interfaceName = INTERFACE_NAME,
            direction = WitBindingDirection.EXPORT,
        )
        val exportedFunctionBinding = createFunctionBinding(
            declaration = exportedFunction,
            bindingName = FUNCTION_BINDING,
            interfaceName = INTERFACE_NAME,
            direction = WitBindingDirection.EXPORT,
        )
        val importedDelegateBinding = createFunctionBinding(
            declaration = importedProperty,
            bindingName = IMPORTED_BINDING,
            interfaceName = INTERFACE_NAME,
            direction = WitBindingDirection.IMPORT,
        )
        val importedFunctionBinding = createFunctionBinding(
            declaration = importedFunction,
            bindingName = IMPORTED_BINDING,
            interfaceName = INTERFACE_NAME,
            direction = WitBindingDirection.IMPORT,
        )
        val resourceMethodDelegateBinding = createResourceBinding(
            declaration = resourceMethodProperty,
            bindingName = RESOURCE_METHOD_BINDING,
            interfaceName = INTERFACE_NAME,
            resourceName = OWNED_RESOURCE,
        )
        val resourceMethodBinding = createFunctionBinding(
            declaration = resourceMethod,
            bindingName = RESOURCE_METHOD_BINDING,
            interfaceName = INTERFACE_NAME,
            direction = WitBindingDirection.EXPORT,
            kind = WitBindingKind.RESOURCE,
            resourceName = OWNED_RESOURCE,
        )

        val world = WitIrPlan.World(
            irClass = worldClass,
            packageId = PACKAGE_ID,
            worldName = WORLD_NAME,
            bindings = listOf(
                owningBinding,
                owningFunctionBinding,
                borrowedBinding,
                borrowedFunctionBinding,
                exportedDelegateBinding,
                exportedFunctionBinding,
                importedDelegateBinding,
                importedFunctionBinding,
                resourceMethodDelegateBinding,
                resourceMethodBinding,
            ),
            resources = emptyList(),
            constructors = listOf(
                WitIrPlan.Constructor(
                    declaration = owningHelper,
                    bindingName = OWNED_BINDING,
                    direction = WitBindingDirection.EXPORT,
                ),
                WitIrPlan.Constructor(
                    declaration = borrowedHelper,
                    bindingName = BORROWED_BINDING,
                    direction = WitBindingDirection.EXPORT,
                ),
            ),
            driver = null,
            runtimeSlot = WitIrPlan.RuntimeSlot(
                property = runtimeProperty,
                backingField = runtimeProperty.backingField
                    ?: error("Runtime property lacks backing field"),
            ),
        )
        return WitIrPlan(listOf(world))
    }

    private fun createResourceBinding(
        declaration: IrDeclaration,
        bindingName: String,
        interfaceName: String,
        resourceName: String,
        resultShape: WitTypeShape = WitTypeShape.Unknown,
    ): WitIrPlan.Binding {
        val prototypes = listOf(
            WitIrPlan.TypeRefPrototype(
                label = null,
                typeRef = "own:$interfaceName/$resourceName",
                shape = resultShape,
            ),
        )
        val results = if (resultShape == WitTypeShape.Unknown) emptyList() else prototypes
        return WitIrPlan.Binding(
            declaration = declaration,
            declarationName = declaration.renderName(),
            direction = WitBindingDirection.EXPORT,
            kind = WitBindingKind.RESOURCE,
            interfaceName = interfaceName,
            resourceName = resourceName,
            bindingName = bindingName,
            runtimeTarget = "",
            isAsync = false,
            usesStreams = false,
            parameterTypes = emptyList(),
            resultTypes = results,
        )
    }

    private fun createFunctionBinding(
        declaration: IrDeclaration,
        bindingName: String,
        interfaceName: String,
        direction: WitBindingDirection,
        kind: WitBindingKind = WitBindingKind.FUNCTION,
        resourceName: String = "",
        parameterPrototypes: List<WitIrPlan.TypeRefPrototype> = emptyList(),
        resultPrototypes: List<WitIrPlan.TypeRefPrototype> = emptyList(),
    ): WitIrPlan.Binding =
        WitIrPlan.Binding(
            declaration = declaration,
            declarationName = declaration.renderName(),
            direction = direction,
            kind = kind,
            interfaceName = interfaceName,
            resourceName = resourceName,
            bindingName = bindingName,
            runtimeTarget = "",
            isAsync = false,
            usesStreams = false,
            parameterTypes = parameterPrototypes,
            resultTypes = resultPrototypes,
        )

    private fun IrDeclaration.renderName(): String =
        when (this) {
            is IrProperty -> name.asString()
            is IrSimpleFunction -> name.asString()
            else -> toString()
        }

    private fun IrClass.innerClasses(): List<IrClass> =
        declarations.filterIsInstance<IrClass>()

    private fun IrClass.findProperty(name: String): IrProperty? =
        declarations.filterIsInstance<IrProperty>().firstOrNull { it.name.asString() == name }

    private fun IrClass.findFunction(name: String): IrSimpleFunction =
        declarations.filterIsInstance<IrSimpleFunction>()
            .firstOrNull { it.name.asString() == name }
            ?: error("Function $name not found in ${this.name.asString()}")

    private fun IrModuleFragment.findClass(fqName: String): IrClass? {
        val target = FqName(fqName)
        files.forEach { file ->
            file.declarations.filterIsInstance<IrClass>().forEach { klass ->
                if (klass.fqNameWhenAvailable == target) return klass
            }
        }
        return null
    }

    private data class FixtureContext(
        val compilation: IrCompilationResult,
        val plan: WitIrPlan,
        val owningFunction: IrSimpleFunction,
        val borrowedFunction: IrSimpleFunction,
        val exportedFunction: IrSimpleFunction,
        val importedFunction: IrSimpleFunction,
        val resourceMethod: IrSimpleFunction,
    )

    companion object {
        private const val PACKAGE_ID = "test:pkg"
        private const val WORLD_NAME = "FixtureWorld"
        private const val INTERFACE_NAME = "fixtures"
        private const val OWNED_RESOURCE = "OwnedResource"
        private const val BORROWED_RESOURCE = "BorrowedResource"
        private const val OWNED_BINDING = "[constructor]owned"
        private const val BORROWED_BINDING = "[constructor]borrowed"
        private const val FUNCTION_BINDING = "[function]exported"
        private const val RESOURCE_METHOD_BINDING = "[method]owned-resource"
        private const val IMPORTED_BINDING = "[function]imported"
        private const val RUNTIME_SLOT_NAME = "__witRuntime"

        @JvmStatic
        @AfterAll
        fun tearDown() {
            TestIrCompiler.disposeAll()
        }

        private val KOTLIN_STUB = TestSourceFile(
            name = "kotlin/UnsupportedOperationException.kt",
            contents =
            """
            package kotlin

            class UnsupportedOperationException(message: String) : RuntimeException(message)
            """.trimIndent(),
        )

        private val RUNTIME_STUB = TestSourceFile(
            name = "RuntimeStub.kt",
            contents =
            """
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
                fun registerImportHandler(packageId: String, worldName: String, bindingName: String, handler: BindingHandler) {}
                fun registerExportHandler(packageId: String, worldName: String, bindingName: String, handler: BindingHandler) {}
                fun registerResource(type: ResourceType, factory: ResourceFactory): ResourceFactory? = null
                fun registerResourceFactory(
                    type: ResourceType,
                    factory: ResourceFactory,
                    constructor: (ComponentRuntime, ResourceFactory, Array<out Any?>) -> Handle<Resource>,
                ) {}
                fun resolveResource(type: ResourceType): ResourceFactory? = null
                fun dispatchBinding(delegate: BindingDelegate, vararg arguments: Any?): Any? = null
            }

            interface Handle<T : Resource>

            fun ComponentRuntime.allocateOwnedHandle(adapter: ResourceAdapter): Handle<Resource> =
                object : Handle<Resource> {}

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
            """.trimIndent(),
        )

        private val WORLD_STUB = TestSourceFile(
            name = "FixtureWorld.kt",
            contents =
            """
            package test

            import org.jetbrains.kotlin.wit.runtime.*

            abstract class FixtureWorld {
                abstract val owningDelegate: BindingDelegate
                abstract val borrowedDelegate: BindingDelegate
                abstract val exportedDelegate: BindingDelegate
                abstract val importedDelegate: BindingDelegate

                abstract fun owningConstructor(flag: Boolean): Any
                abstract fun borrowedConstructor(flag: Boolean): Any
                abstract fun exportedFunction(value: Int, flag: Boolean): Any
                abstract fun importedFunction(value: Int): Any

                abstract class OwnedResource {
                    abstract val methodDelegate: BindingDelegate
                    abstract fun method(value: Int, flag: Boolean): Any
                }

                companion object {
                    var __witRuntime: ComponentRuntime? = null

                    fun owningHelper(
                        runtime: ComponentRuntime,
                        factory: ResourceFactory,
                        flag: Boolean,
                    ): Handle<Resource> = runtime.allocateOwnedHandle(factory.create(runtime))

                    fun borrowedHelper(
                        runtime: ComponentRuntime,
                        factory: ResourceFactory,
                        flag: Boolean,
                    ): Handle<Resource> = runtime.allocateOwnedHandle(factory.create(runtime))
                }
            }
            """.trimIndent(),
        )
    }
}
