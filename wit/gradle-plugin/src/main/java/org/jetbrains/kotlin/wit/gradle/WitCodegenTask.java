package org.jetbrains.kotlin.wit.gradle;

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
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.js.K2JSCompiler;

public abstract class WitCodegenTask extends DefaultTask {
    private static final String WIT_PLUGIN_ID = "org.jetbrains.kotlin.wit.compiler";
    private static final String ARG_WASM = "-Xwasm";
    private static final String ARG_WASM_COMPONENT = "-Xwasm-component";
    private static final String ARG_IR_PRODUCE_KLIB = "-Xir-produce-klib-file";
    private static final String ARG_MODULE_NAME = "-module-name";
    private static final String ARG_OUTPUT_DIR = "-output-dir";
    private static final String ARG_LIBRARIES = "-libraries";
    private static final String ARG_NO_STDLIB = "-no-stdlib";

    private final ConfigurableFileCollection schemaRoots = getProject().files();
    private final ConfigurableFileCollection includeRoots = getProject().files();
    private final ConfigurableFileCollection jsonSchemas = getProject().files();
    private final ConfigurableFileCollection libraries = getProject().files();

    private final ListProperty<String> features = getProject().getObjects().listProperty(String.class).convention(Collections.emptyList());
    private final Property<Boolean> debug = getProject().getObjects().property(Boolean.class).convention(false);
    private final Property<String> moduleName = getProject().getObjects().property(String.class).convention(getProject().getName());
    private final Property<Boolean> noStdlib = getProject().getObjects().property(Boolean.class).convention(false);
    private final DirectoryProperty outputDirectory = getProject().getObjects().directoryProperty();
    private final RegularFileProperty pluginJar = getProject().getObjects().fileProperty();

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
    public Property<Boolean> getNoStdlib() {
        return noStdlib;
    }

    @OutputDirectory
    public DirectoryProperty getOutputDirectory() {
        return outputDirectory;
    }

    public RegularFileProperty getPluginJar() {
        return pluginJar;
    }

    @TaskAction
    public void generate() {
        try {
            Files.createDirectories(outputDirectory.get().getAsFile().toPath());
        } catch (Exception ex) {
            throw new GradleException("Unable to create output directory", ex);
        }

        WitOfflineCompilationConfig config = new WitOfflineCompilationConfig(
                toPaths(schemaRoots.getFiles()),
                toPaths(includeRoots.getFiles()),
                toPaths(jsonSchemas.getFiles()),
                new ArrayList<>(features.getOrElse(Collections.emptyList())),
                debug.getOrElse(false),
                outputDirectory.get().getAsFile().toPath(),
                moduleName.get(),
                toAbsolutePaths(libraries.getFiles()),
                pluginJar.isPresent() ? pluginJar.get().getAsFile().toPath() : locatePluginJar(),
                noStdlib.getOrElse(false)
        );

        List<String> args = WitOfflineCompilerArgumentsBuilder.build(config, getTemporaryDir());

        ByteArrayOutputStream executionLog = new ByteArrayOutputStream();
        ExitCode exitCode;
        try (PrintStream collector = new PrintStream(executionLog)) {
            exitCode = new K2JSCompiler().execFullPathsInMessages(collector, args.toArray(new String[0]));
        }

        if (exitCode != ExitCode.OK) {
            throw new GradleException(
                    "WIT code generation failed (exit code=" + exitCode + ")\n" + executionLog.toString(StandardCharsets.UTF_8)
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
            result.add(file.getAbsolutePath());
        }
        return result;
    }

    private Path locatePluginJar() {
        try {
            var location = WitCodegenTask.class.getProtectionDomain().getCodeSource().getLocation();
            Path path = Paths.get(location.toURI());
            if (Files.isRegularFile(path)) {
                return path;
            }
        } catch (Exception ex) {
            throw new GradleException("Unable to resolve WIT compiler plugin jar", ex);
        }
        throw new GradleException("WIT compiler plugin jar not found on classpath");
    }

    static final class WitOfflineCompilationConfig {
        final List<Path> schemaRoots;
        final List<Path> includeRoots;
        final List<Path> jsonSchemas;
        final List<String> features;
        final boolean debug;
        final Path outputDir;
        final String moduleName;
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
                        "@file:Suppress(\"unused\")\n" +
                        "package org.jetbrains.kotlin.wit.generated\n\n" +
                        "internal object __WitAnchor\n"
                );
            } catch (Exception ex) {
                throw new GradleException("Unable to prepare temporary sources", ex);
            }

            List<String> args = new ArrayList<>();
            args.add(ARG_WASM);
            args.add(ARG_WASM_COMPONENT);
            args.add(ARG_IR_PRODUCE_KLIB);
            args.add(ARG_MODULE_NAME);
            args.add(config.moduleName);
            args.add(ARG_OUTPUT_DIR);
            args.add(config.outputDir.toAbsolutePath().toString());

            if (!config.libraries.isEmpty()) {
                args.add(ARG_LIBRARIES);
                args.add(String.join(File.pathSeparator, config.libraries));
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
            return args;
        }

        private static void pluginOption(List<String> args, String name, String value) {
            args.add("-P");
            args.add("plugin:" + WIT_PLUGIN_ID + ":" + name + "=" + value);
        }
    }
}
