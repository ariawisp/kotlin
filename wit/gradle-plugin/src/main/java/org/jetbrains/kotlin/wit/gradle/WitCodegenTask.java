package org.jetbrains.kotlin.wit.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.js.K2JSCompiler;
import javax.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class WitCodegenTask extends DefaultTask {
    private static final String WIT_PLUGIN_ID = "org.jetbrains.kotlin.wit.compiler";
    private static final String ARG_WASM = "-Xwasm";
    private static final String ARG_WASM_COMPONENT = "-Xwasm-component";
    private static final String ARG_IR_PRODUCE_KLIB = "-Xir-produce-klib-file";
    private static final String ARG_MODULE_NAME = "-module-name";
    private static final String ARG_IR_MODULE_NAME = "-Xir-module-name";
    private static final String ARG_OUTPUT_DIR = "-output-dir";
    private static final String ARG_IR_OUTPUT_DIR = "-ir-output-dir";
   private static final String ARG_LIBRARIES = "-libraries";
    private static final String ARG_NO_STDLIB = "-no-stdlib";

    private final ConfigurableFileCollection schemaRoots;
    private final ConfigurableFileCollection includeRoots;
    private final ConfigurableFileCollection jsonSchemas;
    private final ConfigurableFileCollection libraries;

    private final ListProperty<String> features;
    private final Property<Boolean> debug;
    private final Property<String> moduleName;
    private final Property<String> wasmTarget;
    private final Property<Boolean> noStdlib;
    private final DirectoryProperty outputDirectory;
    private final RegularFileProperty pluginJar;

    @Inject
    public WitCodegenTask(ObjectFactory objects) {
        this.schemaRoots = objects.fileCollection();
        this.includeRoots = objects.fileCollection();
        this.jsonSchemas = objects.fileCollection();
        this.libraries = objects.fileCollection();

        this.features = objects.listProperty(String.class);
        this.features.convention(Collections.emptyList());

        this.debug = objects.property(Boolean.class);
        this.debug.convention(false);

        this.moduleName = objects.property(String.class);
        this.moduleName.convention(getProject().getName());

        this.wasmTarget = objects.property(String.class);
        this.wasmTarget.convention("wasm-wasi");

        this.noStdlib = objects.property(Boolean.class);
        this.noStdlib.convention(false);

        this.outputDirectory = objects.directoryProperty();
        this.pluginJar = objects.fileProperty();
    }

    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    public ConfigurableFileCollection getSchemaRoots() {
        return schemaRoots;
    }

    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    public ConfigurableFileCollection getIncludeRoots() {
        return includeRoots;
    }

    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    public ConfigurableFileCollection getJsonSchemas() {
        return jsonSchemas;
    }

    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    public ConfigurableFileCollection getLibraries() {
        return libraries;
    }

    @Input
    public ListProperty<String> getFeatures() {
        return features;
    }

    @Input
    public Property<Boolean> getDebug() {
        return debug;
    }

    @Input
    public Property<String> getModuleName() {
        return moduleName;
    }

    @Input
    public Property<String> getWasmTarget() {
        return wasmTarget;
    }

    @Input
    public Property<Boolean> getNoStdlib() {
        return noStdlib;
    }

    @OutputDirectory
    public DirectoryProperty getOutputDirectory() {
        return outputDirectory;
    }

    @Optional
    @InputFile
    public RegularFileProperty getPluginJar() {
        return pluginJar;
    }

    @TaskAction
    public void generate() {
        // Quick classpath sanity checks when debug is enabled
        if (Boolean.TRUE.equals(debug.getOrElse(false))) {
            try {
                Class<?> pce = Class.forName("org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException");
                getLogger().lifecycle("[WIT] Sanity: Found PCE class at {}", pce.getProtectionDomain().getCodeSource());
            } catch (Throwable t) {
                getLogger().warn("[WIT] Sanity: Missing PCE class (org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException): {}", t.toString());
            }
            try {
                Class<?> k2 = Class.forName("org.jetbrains.kotlin.cli.js.K2JSCompiler");
                getLogger().lifecycle("[WIT] Sanity: Found K2JSCompiler at {}", k2.getProtectionDomain().getCodeSource());
            } catch (Throwable t) {
                getLogger().warn("[WIT] Sanity: Missing K2JSCompiler: {}", t.toString());
            }
        }

        try {
            Files.createDirectories(outputDirectory.get().getAsFile().toPath());
        } catch (Exception ex) {
            throw new GradleException("Unable to create output directory", ex);
        }

        if (!pluginJar.isPresent()) {
            throw new GradleException("WIT pluginJar is not set. Configure WitCodegenTask.pluginJar to point to the compiler plugin JAR.");
        }

        List<String> configuredLibraries = toAbsolutePaths(libraries.getFiles());
        if (Boolean.TRUE.equals(debug.getOrElse(false))) {
            getLogger().lifecycle("[WIT] Configured klib inputs={}", configuredLibraries);
        }

        WitOfflineCompilationConfig config = new WitOfflineCompilationConfig(
                toPaths(schemaRoots.getFiles()),
                toPaths(includeRoots.getFiles()),
                toPaths(jsonSchemas.getFiles()),
                new ArrayList<>(features.getOrElse(Collections.emptyList())),
                debug.getOrElse(false),
                outputDirectory.get().getAsFile().toPath(),
                moduleName.get(),
                wasmTarget.getOrElse("wasm-wasi"),
                configuredLibraries,
                pluginJar.get().getAsFile().toPath(),
                noStdlib.getOrElse(false)
        );

        List<String> args = WitOfflineCompilerArgumentsBuilder.build(config, getTemporaryDir());

        ByteArrayOutputStream executionLog = new ByteArrayOutputStream();
        try (PrintStream collector = new PrintStream(executionLog)) {
            ClassLoader isolated = createIsolatedCompilerClassLoader();
            try {
                Class<?> k2Class = Class.forName("org.jetbrains.kotlin.cli.js.K2JSCompiler", true, isolated);
                Object compiler = k2Class.getDeclaredConstructor().newInstance();
                java.lang.reflect.Method exec = k2Class.getMethod("execFullPathsInMessages", PrintStream.class, String[].class);
                Object exitCode = exec.invoke(compiler, collector, args.toArray(new String[0]));

                String exitName = (String) exitCode.getClass().getMethod("name").invoke(exitCode);
                if (!"OK".equals(exitName)) {
                    throw new GradleException(
                            "WIT code generation failed (exit code=" + exitName + ")\n" + executionLog.toString(StandardCharsets.UTF_8)
                    );
                }
            } finally {
                tryClose(isolated);
            }
        } catch (GradleException ex) {
            throw ex;
        } catch (Throwable t) {
            throw new GradleException(
                    "WIT code generation failed due to unexpected error: " + t,
                    t
            );
        }

        if (getLogger().isInfoEnabled()) {
            getLogger().info("Generated WIT klib '{}' using {} schema root(s)", config.moduleName, config.schemaRoots.size());
        }
    }

    private static List<Path> toPaths(Iterable<File> files) {
        List<Path> paths = new ArrayList<>();
        for (File file : files) {
            paths.add(file.toPath());
        }
        return paths;
    }

    private static List<String> toAbsolutePaths(Iterable<File> files) {
        List<String> result = new ArrayList<>();
        for (File file : files) {
            if (file.exists()) {
                result.add(file.getAbsolutePath());
            }
        }
        return result;
    }

    private static final String K2_COMPILER_RESOURCE = "org/jetbrains/kotlin/cli/js/K2JSCompiler.class";

    private ClassLoader createIsolatedCompilerClassLoader() {
        try {
            // Find the kotlin-compiler-embeddable jar by locating the compiler class resource on the current classpath
            java.net.URL compilerRes = null;
            var resources = WitCodegenTask.class.getClassLoader().getResources(K2_COMPILER_RESOURCE);
            while (resources.hasMoreElements()) {
                java.net.URL url = resources.nextElement();
                if ("jar".equals(url.getProtocol())) {
                    compilerRes = url;
                    break;
                }
            }
            if (compilerRes == null) {
                // Fallback: scan java.class.path for a kotlin-compiler-embeddable jar
                String cp = System.getProperty("java.class.path", "");
                for (String entry : cp.split(java.io.File.pathSeparator)) {
                    if (entry.contains("kotlin-compiler-embeddable") && entry.endsWith(".jar")) {
                        compilerRes = new java.io.File(entry).toURI().toURL();
                        break;
                    }
                }
            }

            if (compilerRes == null) {
                throw new GradleException("Unable to locate kotlin-compiler-embeddable on classpath");
            }

            java.net.URL compilerJarUrl;
            if ("jar".equals(compilerRes.getProtocol())) {
                // jar:file:/.../kotlin-compiler-embeddable-xxx.jar!/org/...
                String spec = compilerRes.getFile();
                int bang = spec.indexOf("!");
                String jarSpec = (bang >= 0 ? spec.substring(0, bang) : spec);
                java.net.URL jarUrl = new java.net.URL(jarSpec);
                if (!"file".equals(jarUrl.getProtocol())) {
                    throw new GradleException("Unsupported compiler URL protocol: " + jarUrl);
                }
                compilerJarUrl = new java.net.URL(jarUrl.toString());
            } else {
                compilerJarUrl = compilerRes;
            }

            java.util.List<java.net.URL> urls = new java.util.ArrayList<>();
            urls.add(compilerJarUrl);

            // Add Kotlin stdlib from local Maven if present (needed by compiler and plugins)
            try {
                String userHome = System.getProperty("user.home", "");
                // stdlib
                Path stdlibDir = Paths.get(userHome, ".m2", "repository", "org", "jetbrains", "kotlin", "kotlin-stdlib");
                if (Files.isDirectory(stdlibDir)) {
                    try (var versions = Files.list(stdlibDir)) {
                        Path latest = versions.filter(Files::isDirectory)
                                .sorted((a, b) -> { try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); } catch (Exception e) { return 0; } })
                                .findFirst().orElse(null);
                        if (latest != null) {
                            Path stdlibJar = latest.resolve("kotlin-stdlib-" + latest.getFileName().toString() + ".jar");
                            if (Files.isRegularFile(stdlibJar)) urls.add(stdlibJar.toUri().toURL());
                        }
                    }
                }
                // reflect
                Path reflectDir = Paths.get(userHome, ".m2", "repository", "org", "jetbrains", "kotlin", "kotlin-reflect");
                if (Files.isDirectory(reflectDir)) {
                    try (var versions = Files.list(reflectDir)) {
                        Path latest = versions.filter(Files::isDirectory)
                                .sorted((a, b) -> { try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); } catch (Exception e) { return 0; } })
                                .findFirst().orElse(null);
                        if (latest != null) {
                            Path reflectJar = latest.resolve("kotlin-reflect-" + latest.getFileName().toString() + ".jar");
                            if (Files.isRegularFile(reflectJar)) urls.add(reflectJar.toUri().toURL());
                        }
                    }
                }
                // coroutines
                Path coroutinesDir = Paths.get(userHome, ".m2", "repository", "org", "jetbrains", "kotlinx", "kotlinx-coroutines-core-jvm");
                if (Files.isDirectory(coroutinesDir)) {
                    try (var versions = Files.list(coroutinesDir)) {
                        Path latest = versions.filter(Files::isDirectory)
                                .sorted((a, b) -> { try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); } catch (Exception e) { return 0; } })
                                .findFirst().orElse(null);
                        if (latest != null) {
                            // Try modern artifact name first; fallback to plain core if needed
                            Path coroutinesJar = latest.resolve("kotlinx-coroutines-core-jvm-" + latest.getFileName().toString() + ".jar");
                            if (!Files.isRegularFile(coroutinesJar)) {
                                coroutinesJar = latest.resolve("kotlinx-coroutines-core-" + latest.getFileName().toString() + ".jar");
                            }
                            if (Files.isRegularFile(coroutinesJar)) urls.add(coroutinesJar.toUri().toURL());
                        }
                    }
                }
            } catch (Exception ignored) {}

            // Note: Compiler plugin JAR is supplied via -Xplugin and does not need to be on the classpath

            if (Boolean.TRUE.equals(debug.getOrElse(false)) && getLogger().isLifecycleEnabled()) {
                getLogger().lifecycle("[WIT] Using isolated compiler urls {}", urls);
            }

            // Isolated from Gradle's plugin classloader
            return new java.net.URLClassLoader(urls.toArray(new java.net.URL[0]), null);
        } catch (Exception ex) {
            throw new GradleException("Failed to create isolated compiler classloader", ex);
        }
    }

    private static void tryClose(ClassLoader cl) {
        if (cl instanceof java.io.Closeable closeable) {
            try { closeable.close(); } catch (Exception ignored) {}
        }
    }

    private Path locatePluginJar() {
        // Prefer a locally built compiler plugin jar from the repo to avoid cross-project task cycles
        try {
            Path root = getProject().getRootProject().getProjectDir().toPath();
            Path libsDir = root.resolve("wit/compiler-plugin/build/libs");
            if (Files.isDirectory(libsDir)) {
                try (var stream = Files.list(libsDir)) {
                    Path candidate = stream
                            .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().startsWith("compiler-plugin-") && p.getFileName().toString().endsWith(".jar"))
                            .sorted((a, b) -> {
                                try {
                                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                                } catch (Exception e) { return 0; }
                            })
                            .findFirst()
                            .orElse(null);
                    if (candidate != null) return candidate;
                }
            }
        } catch (Exception ignored) {}

        // Fallback: try the location of this Gradle plugin jar (won't work as a compiler plugin)
        try {
            var location = WitCodegenTask.class.getProtectionDomain().getCodeSource().getLocation();
            Path path = Paths.get(location.toURI());
            if (Files.isRegularFile(path)) {
                return path;
            }
        } catch (Exception ex) {
            throw new GradleException("Unable to resolve WIT compiler plugin jar", ex);
        }
        throw new GradleException("WIT compiler plugin jar not found. Please run ':wit:compiler-plugin:jar' or set 'pluginJar'.");
    }

    static final class WitOfflineCompilationConfig {
        final List<Path> schemaRoots;
        final List<Path> includeRoots;
        final List<Path> jsonSchemas;
        final List<String> features;
        final boolean debug;
        final Path outputDir;
        final String moduleName;
        final String wasmTarget;
        final List<String> libraries;
        final Path pluginJar;
        final boolean noStdlib;

        WitOfflineCompilationConfig(
                List<Path> schemaRoots,
                List<Path> includeRoots,
                List<Path> jsonSchemas,
                List<String> features,
                boolean debug,
                Path outputDir,
                String moduleName,
                String wasmTarget,
                List<String> libraries,
                Path pluginJar,
                boolean noStdlib
        ) {
            this.schemaRoots = schemaRoots;
            this.includeRoots = includeRoots;
            this.jsonSchemas = jsonSchemas;
            this.features = features;
            this.debug = debug;
            this.outputDir = outputDir;
            this.moduleName = moduleName;
            this.wasmTarget = wasmTarget;
            this.libraries = libraries;
            this.pluginJar = pluginJar;
            this.noStdlib = noStdlib;
        }
    }

    static final class WitOfflineCompilerArgumentsBuilder {
        static List<String> build(WitOfflineCompilationConfig config, File tempDir) {
            Path stubSource;
            try {
                stubSource = tempDir.toPath().resolve("__witAnchor.kt");
                Files.writeString(
                        stubSource,
                        "package org.jetbrains.kotlin.wit.generated\n"
                );
            } catch (Exception ex) {
                throw new GradleException("Unable to prepare temporary sources", ex);
            }

            List<String> args = new ArrayList<>();
            args.add(ARG_WASM);
            args.add(ARG_WASM_COMPONENT);
            args.add(ARG_IR_PRODUCE_KLIB);
            args.add(ARG_IR_MODULE_NAME + "=" + config.moduleName);
            args.add(ARG_IR_OUTPUT_DIR);
            args.add(config.outputDir.toAbsolutePath().toString());
            args.add("-ir-output-name");
            args.add(config.moduleName);
            if (config.wasmTarget != null && !config.wasmTarget.isEmpty()) {
                args.add("-Xwasm-target=" + config.wasmTarget);
            }

            // Include libraries if provided; otherwise, try to auto-include the wasm stdlib klib from Maven local
            List<String> libs = new ArrayList<>(config.libraries);
            if (libs.isEmpty()) {
                try {
                    Path m2 = Paths.get(System.getProperty("user.home"), ".m2", "repository", "org", "jetbrains", "kotlin", "kotlin-stdlib-wasm-wasi");
                    if (Files.isDirectory(m2)) {
                        try (var versions = Files.list(m2)) {
                            Path latest = versions
                                    .filter(Files::isDirectory)
                                    .sorted((a, b) -> {
                                        try {
                                            return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                                        } catch (Exception e) { return 0; }
                                    })
                                    .findFirst()
                                    .orElse(null);
                            if (latest != null) {
                                try (var files = Files.list(latest)) {
                                    Path klib = files
                                            .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".klib"))
                                            .findFirst()
                                            .orElse(null);
                                    if (klib != null) {
                                        libs.add(klib.toAbsolutePath().toString());
                                    }
                                }
                            }
                        }
                    }
                    // No-op: additional transitive klibs (like kotlinx-atomicfu-runtime) can be provided via the Gradle task configuration
                } catch (Exception ignored) {}
            }
            if (!libs.isEmpty()) {
                if (Boolean.TRUE.equals(config.debug)) {
                    System.out.println("[WIT] Using klib libraries=" + libs);
                }
                args.add(ARG_LIBRARIES);
                args.add(String.join(File.pathSeparator, libs));
            }

            if (config.noStdlib) {
                args.add(ARG_NO_STDLIB);
            }

            args.add("-Xplugin=" + config.pluginJar.toAbsolutePath());
            pluginOption(args, "enabled", "true");

            for (Path root : config.schemaRoots) {
                pluginOption(args, "root", root.toAbsolutePath().toString());
            }
            for (Path include : config.includeRoots) {
                pluginOption(args, "include", include.toAbsolutePath().toString());
            }
            for (Path schema : config.jsonSchemas) {
                pluginOption(args, "json", schema.toAbsolutePath().toString());
            }
            for (String feature : config.features) {
                pluginOption(args, "feature", feature);
            }
            if (config.debug) {
                pluginOption(args, "debug", "true");
            }

            args.add(stubSource.toAbsolutePath().toString());
            if (config.debug) {
                System.out.println("[WIT] Compiler args=" + args);
            }
            return args;
        }

        private static void pluginOption(List<String> args, String name, String value) {
            args.add("-P");
            args.add("plugin:" + WIT_PLUGIN_ID + ":" + name + "=" + value);
        }
    }
}
