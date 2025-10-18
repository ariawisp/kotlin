package org.jetbrains.kotlin.wit.gradle;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Minimal task to compile a set of Kotlin sources to a wasm component .klib using the embedded K2 compiler.
 * This avoids publishing to mavenLocal while allowing downstream isolated compilers to resolve runtime classes.
 */
public abstract class WasmKlibTask extends DefaultTask {
    private static final String ARG_WASM = "-Xwasm";
    private static final String ARG_WASM_COMPONENT = "-Xwasm-component";
    private static final String ARG_IR_PRODUCE_KLIB = "-Xir-produce-klib-file";
    private static final String ARG_IR_MODULE_NAME = "-Xir-module-name";
    private static final String ARG_IR_OUTPUT_DIR = "-ir-output-dir";
    private static final String ARG_IR_OUTPUT_NAME = "-ir-output-name";
    private static final String ARG_LIBRARIES = "-libraries";

    private final ConfigurableFileCollection sources = getProject().files();
    private final ConfigurableFileCollection libraries = getProject().files();
    private final Property<String> moduleName = getProject().getObjects().property(String.class).convention(getProject().getName());
    private final DirectoryProperty outputDirectory = getProject().getObjects().directoryProperty();
    private final Property<Boolean> debug = getProject().getObjects().property(Boolean.class).convention(false);

    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    public ConfigurableFileCollection getSources() { return sources; }

    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    public ConfigurableFileCollection getLibraries() { return libraries; }

    @Input
    public Property<String> getModuleName() { return moduleName; }

    @Input
    public Property<Boolean> getDebug() { return debug; }

    @OutputDirectory
    public DirectoryProperty getOutputDirectory() { return outputDirectory; }

    @TaskAction
    public void compile() {
        try {
            Files.createDirectories(outputDirectory.get().getAsFile().toPath());
        } catch (Exception ex) {
            throw new GradleException("Unable to create output directory", ex);
        }

        List<String> args = new ArrayList<>();
        args.add(ARG_WASM);
        args.add(ARG_WASM_COMPONENT);
        args.add(ARG_IR_PRODUCE_KLIB);
        args.add(ARG_IR_MODULE_NAME + "=" + moduleName.get());
        args.add(ARG_IR_OUTPUT_DIR);
        args.add(outputDirectory.get().getAsFile().toPath().toAbsolutePath().toString());
        args.add(ARG_IR_OUTPUT_NAME);
        args.add(moduleName.get());

        if (!libraries.getFiles().isEmpty()) {
            args.add(ARG_LIBRARIES);
            args.add(String.join(File.pathSeparator, toAbsolutePaths(libraries.getFiles())));
        }

        for (File src : sources.getFiles()) {
            if (src.isFile() && src.getName().endsWith(".kt")) {
                args.add(src.getAbsolutePath());
            }
        }

        ByteArrayOutputStream executionLog = new ByteArrayOutputStream();
        try (PrintStream collector = new PrintStream(executionLog)) {
            ClassLoader isolated = createIsolatedCompilerClassLoader(debug.getOrElse(false));
            try {
                Class<?> k2Class = Class.forName("org.jetbrains.kotlin.cli.js.K2JSCompiler", true, isolated);
                Object compiler = k2Class.getDeclaredConstructor().newInstance();
                java.lang.reflect.Method exec = k2Class.getMethod("execFullPathsInMessages", PrintStream.class, String[].class);
                Object exitCode = exec.invoke(compiler, collector, args.toArray(new String[0]));

                String exitName = (String) exitCode.getClass().getMethod("name").invoke(exitCode);
                if (!"OK".equals(exitName)) {
                    throw new GradleException(
                            "Wasm klib compilation failed (exit code=" + exitName + ")\n" + executionLog.toString(StandardCharsets.UTF_8)
                    );
                }
            } finally {
                tryClose(isolated);
            }
        } catch (GradleException ex) {
            throw ex;
        } catch (Throwable t) {
            throw new GradleException("Wasm klib compilation failed due to unexpected error: " + t, t);
        }
    }

    private static List<String> toAbsolutePaths(Iterable<File> files) {
        List<String> result = new ArrayList<>();
        for (File f : files) result.add(f.getAbsolutePath());
        return result;
    }

    private static void tryClose(ClassLoader cl) {
        if (cl instanceof java.io.Closeable closeable) {
            try { closeable.close(); } catch (Exception ignored) {}
        }
    }

    private static final String K2_COMPILER_RESOURCE = "org/jetbrains/kotlin/cli/js/K2JSCompiler.class";

    private static ClassLoader createIsolatedCompilerClassLoader(boolean debug) {
        try {
            URL compilerRes = null;
            var resources = WasmKlibTask.class.getClassLoader().getResources(K2_COMPILER_RESOURCE);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                if ("jar".equals(url.getProtocol())) { compilerRes = url; break; }
            }
            if (compilerRes == null) {
                String cp = System.getProperty("java.class.path", "");
                for (String entry : cp.split(File.pathSeparator)) {
                    if (entry.contains("kotlin-compiler-embeddable") && entry.endsWith(".jar")) {
                        compilerRes = new File(entry).toURI().toURL();
                        break;
                    }
                }
            }
            if (compilerRes == null) throw new GradleException("Unable to locate kotlin-compiler-embeddable on classpath");

            URL compilerJarUrl;
            if ("jar".equals(compilerRes.getProtocol())) {
                String spec = compilerRes.getFile();
                int bang = spec.indexOf("!");
                String jarSpec = (bang >= 0 ? spec.substring(0, bang) : spec);
                URL jarUrl = new URL(jarSpec);
                if (!"file".equals(jarUrl.getProtocol())) throw new GradleException("Unsupported compiler URL protocol: " + jarUrl);
                compilerJarUrl = new URL(jarUrl.toString());
            } else {
                compilerJarUrl = compilerRes;
            }

            List<URL> urls = new ArrayList<>();
            urls.add(compilerJarUrl);

            // Try to add stdlib/reflect/coroutines from local Maven as convenience
            try {
                String userHome = System.getProperty("user.home", "");
                Path stdlibDir = Paths.get(userHome, ".m2", "repository", "org", "jetbrains", "kotlin", "kotlin-stdlib");
                if (Files.isDirectory(stdlibDir)) {
                    try (var versions = Files.list(stdlibDir)) {
                        Path latest = versions.filter(Files::isDirectory)
                                .sorted((a, b) -> { try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); } catch (Exception e) { return 0; } })
                                .findFirst().orElse(null);
                        if (latest != null) {
                            Path jar = latest.resolve("kotlin-stdlib-" + latest.getFileName().toString() + ".jar");
                            if (Files.isRegularFile(jar)) urls.add(jar.toUri().toURL());
                        }
                    }
                }
                Path reflectDir = Paths.get(userHome, ".m2", "repository", "org", "jetbrains", "kotlin", "kotlin-reflect");
                if (Files.isDirectory(reflectDir)) {
                    try (var versions = Files.list(reflectDir)) {
                        Path latest = versions.filter(Files::isDirectory)
                                .sorted((a, b) -> { try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); } catch (Exception e) { return 0; } })
                                .findFirst().orElse(null);
                        if (latest != null) {
                            Path jar = latest.resolve("kotlin-reflect-" + latest.getFileName().toString() + ".jar");
                            if (Files.isRegularFile(jar)) urls.add(jar.toUri().toURL());
                        }
                    }
                }
                Path coroutinesDir = Paths.get(userHome, ".m2", "repository", "org", "jetbrains", "kotlinx", "kotlinx-coroutines-core-jvm");
                if (Files.isDirectory(coroutinesDir)) {
                    try (var versions = Files.list(coroutinesDir)) {
                        Path latest = versions.filter(Files::isDirectory)
                                .sorted((a, b) -> { try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); } catch (Exception e) { return 0; } })
                                .findFirst().orElse(null);
                        if (latest != null) {
                            Path jar = latest.resolve("kotlinx-coroutines-core-jvm-" + latest.getFileName().toString() + ".jar");
                            if (!Files.isRegularFile(jar)) jar = latest.resolve("kotlinx-coroutines-core-" + latest.getFileName().toString() + ".jar");
                            if (Files.isRegularFile(jar)) urls.add(jar.toUri().toURL());
                        }
                    }
                }
            } catch (Exception ignored) {}

            if (debug) System.out.println("[WASM-KLIB] Using isolated compiler urls " + urls);
            return new java.net.URLClassLoader(urls.toArray(new URL[0]), null);
        } catch (Exception ex) {
            throw new GradleException("Failed to create isolated compiler classloader", ex);
        }
    }
}

