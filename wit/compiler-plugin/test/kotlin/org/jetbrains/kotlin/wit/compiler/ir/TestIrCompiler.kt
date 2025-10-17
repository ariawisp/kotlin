package org.jetbrains.kotlin.wit.compiler.ir

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.PsiFileFactoryImpl
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoot
import org.jetbrains.kotlin.cli.jvm.compiler.CliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.codegen.ClassBuilderFactories
import org.jetbrains.kotlin.codegen.forTestCompile.ForTestCompileRuntime
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.AnalyzingUtils
import org.jetbrains.kotlin.codegen.forTestCompile.TestCompilePaths
import org.jetbrains.kotlin.test.ConfigurationKind
import org.jetbrains.kotlin.test.KotlinTestUtils
import org.jetbrains.kotlin.test.TestJdkKind
import org.jetbrains.kotlin.test.util.KtTestUtil
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.asSequence

internal data class TestSourceFile(
    val name: String,
    val contents: String,
) {
    fun toKtFile(environment: KotlinCoreEnvironment): KtFile {
        val project = environment.project
        val normalizedName = name.substringAfterLast('/')
        val virtualFile = LightVirtualFile(
            normalizedName,
            KotlinLanguage.INSTANCE,
            contents
        )
        @Suppress("UnstableApiUsage")
        val psiFactory = PsiFileFactory.getInstance(project) as PsiFileFactoryImpl
        val ktFile = psiFactory.trySetupPsiForFile(virtualFile, KotlinLanguage.INSTANCE, true, false) as KtFile
        AnalyzingUtils.checkForSyntacticErrors(ktFile)
        return ktFile
    }
}

internal data class IrCompilationResult(
    val moduleFragment: IrModuleFragment,
    val pluginContext: org.jetbrains.kotlin.backend.common.extensions.IrPluginContext,
) {
    fun dumpKotlinLike(): String =
        moduleFragment.files.joinToString(separator = "\n") { file -> file.dumpKotlinLike() }
}

@OptIn(K1Deprecation::class)
internal object TestIrCompiler {
    init {
        System.setProperty("kotlin.environment.keepAlive", "true")
        ensureRuntimeArtifacts()
    }

    private val environmentDisposable: Disposable = Disposer.newDisposable("TestIrCompiler.environment")
    private val environment: KotlinCoreEnvironment by lazy { createEnvironment() }
    private var disposed: Boolean = false

    fun compile(vararg sources: TestSourceFile): IrCompilationResult {
        require(sources.isNotEmpty()) { "At least one source file is required" }
        val ktFiles = sources.map { it.toKtFile(environment) }
        @Suppress("DEPRECATION")
        val analysisResult = TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
            environment.project,
            ktFiles,
            CliBindingTrace(environment.project),
            environment.configuration,
            environment::createPackagePartProvider,
        )
        analysisResult.throwIfError()
        AnalyzingUtils.throwExceptionOnErrors(analysisResult.bindingContext)

        val codegenFactory = org.jetbrains.kotlin.backend.jvm.JvmIrCodegenFactory(environment.configuration)
        val generationState = GenerationState(
            environment.project,
            analysisResult.moduleDescriptor,
            environment.configuration,
            ClassBuilderFactories.TEST,
        )

        val backendInput = codegenFactory.convertToIr(
            generationState,
            ktFiles,
            analysisResult.bindingContext,
        )

        val pluginContext = backendInput.pluginContext
            ?: error("Plugin context is not available after IR conversion")

        return IrCompilationResult(
            moduleFragment = backendInput.irModuleFragment,
            pluginContext = pluginContext,
        )
    }

    private fun createEnvironment(): KotlinCoreEnvironment {
        val configuration = KotlinTestUtils.newConfiguration(
            ConfigurationKind.ALL,
            TestJdkKind.MOCK_JDK,
            KtTestUtil.getAnnotationsJar(),
        )
        configuration.put(CommonConfigurationKeys.USE_FIR, false)
        configuration.put(JVMConfigurationKeys.JVM_TARGET, JvmTarget.JVM_11)
        configuration.put(JVMConfigurationKeys.USE_PSI_CLASS_FILES_READING, true)
        configuration.languageVersionSettings = LanguageVersionSettingsImpl(
            LanguageVersion.LATEST_STABLE,
            ApiVersion.LATEST_STABLE,
        )
        configuration.put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, TestMessageCollector)
        configuration.addJvmClasspathRoot(ForTestCompileRuntime.runtimeJarForTests())
        configuration.addJvmClasspathRoot(ForTestCompileRuntime.kotlinTestJarForTests())
        configuration.addJvmClasspathRoot(ForTestCompileRuntime.scriptRuntimeJarForTests())
        configuration.addJvmClasspathRoot(ForTestCompileRuntime.reflectJarForTests())
        return KotlinCoreEnvironment.createForTests(
            environmentDisposable,
            configuration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
    }

    fun disposeAll() {
        if (!disposed) {
            val application = ApplicationManager.getApplication()
            if (application != null) {
                application.runWriteAction {
                    Disposer.dispose(environmentDisposable)
                }
            } else {
                Disposer.dispose(environmentDisposable)
            }
            disposed = true
        }
    }

    private fun ensureRuntimeArtifacts() {
        setIfMissing(TestCompilePaths.KOTLIN_FULL_STDLIB_PATH) {
            locateArtifact("libraries/stdlib/build/libs") { name ->
                name.startsWith("kotlin-stdlib-") &&
                    name.endsWith(".jar") &&
                    name.noneMatch(listOf("sources", "javadoc", "metadata", "component", "shadow", "stripped"))
            }
        }
        setIfMissing(TestCompilePaths.KOTLIN_TEST_JAR_PATH) {
            locateArtifact("libraries/kotlin.test/build/libs") { name ->
                name.startsWith("kotlin-test-") &&
                    name.endsWith(".jar") &&
                    listOf("sources", "javadoc", "-junit", "-junit5", "-testng", "-js", "-metadata").all { it !in name }
            }
        }
        setIfMissing(TestCompilePaths.KOTLIN_REFLECT_JAR_PATH) {
            locateArtifact("libraries/reflect/build/libs") { name ->
                name.startsWith("kotlin-reflect-") &&
                    name.endsWith(".jar") &&
                    listOf("sources", "javadoc", "shadow", "stripped").all { it !in name }
            }
        }
        setIfMissing(TestCompilePaths.KOTLIN_SCRIPT_RUNTIME_PATH) {
            locateArtifact("libraries/tools/script-runtime/build/libs") { name ->
                name.startsWith("kotlin-script-runtime-") &&
                    name.endsWith(".jar") &&
                    listOf("sources", "javadoc").all { it !in name }
            }
        }
        setIfMissing(TestCompilePaths.KOTLIN_ANNOTATIONS_PATH) {
            locateArtifact("libraries/tools/kotlin-annotations-jvm/build/libs") { name ->
                name.startsWith("kotlin-annotations-jvm") &&
                    name.endsWith(".jar") &&
                    listOf("sources", "javadoc").all { it !in name }
            }
        }
        setIfMissing(TestCompilePaths.KOTLIN_COMMON_STDLIB_PATH) {
            locateArtifact("libraries/stdlib/build/libs") { name ->
                name.startsWith("kotlin-stdlib-metadata") && name.endsWith(".klib")
            }
        }
        setIfMissing(TestCompilePaths.KOTLIN_MOCKJDK_RUNTIME_PATH) {
            val file = File("compiler/testData/mockJDK/jre/lib/rt.jar")
            file.takeIf { it.exists() }
        }
        setIfMissing(TestCompilePaths.KOTLIN_MOCKJDK_ANNOTATIONS_PATH) {
            val file = File("compiler/testData/mockJDK/jre/lib/annotations.jar")
            file.takeIf { it.exists() }
        }
        setIfMissing(TestCompilePaths.KOTLIN_MOCKJDKMODIFIED_RUNTIME_PATH) {
            val file = File("compiler/testData/mockJDKModified/jre/lib/rt.jar")
            file.takeIf { it.exists() }
        }
    }

    private fun setIfMissing(property: String, supplier: () -> File?) {
        if (!System.getProperty(property).isNullOrBlank()) return
        val file = supplier()
        if (file != null && file.exists()) {
            System.setProperty(property, file.absolutePath)
        }
    }

    private fun locateArtifact(root: String, predicate: (String) -> Boolean): File? {
        val base = Paths.get(root)
        if (!Files.isDirectory(base)) return null
        Files.list(base).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) }
                .map(Path::toFile)
                .asSequence()
                .filter { predicate(it.name) }
                .minByOrNull { it.name.length }
        }
    }

    private fun String.noneMatch(disallowed: List<String>): Boolean = disallowed.all { it !in this }
}

private object TestMessageCollector : MessageCollector {
    override fun clear() = Unit

    override fun hasErrors(): Boolean = false

    override fun report(
        severity: CompilerMessageSeverity,
        message: String,
        location: CompilerMessageSourceLocation?,
    ) {
        if (severity == CompilerMessageSeverity.ERROR || severity == CompilerMessageSeverity.EXCEPTION) {
            val prefix = location?.let { "(${it.path}:${it.line}:${it.column}) " }.orEmpty()
            error("Compilation error: $prefix$message")
        }
    }
}
