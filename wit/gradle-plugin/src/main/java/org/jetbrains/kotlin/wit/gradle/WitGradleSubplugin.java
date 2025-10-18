package org.jetbrains.kotlin.wit.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.Action;
import org.gradle.api.tasks.TaskProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation;
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin;
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact;
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WitGradleSubplugin implements KotlinCompilerPluginSupportPlugin, Plugin<Project> {
    public static class WitExtension {
        private final Project project;
        private final Property<Boolean> debug;
        private final Property<Boolean> noStdlib;
        private final ListProperty<String> roots;
        private final ListProperty<String> includes;
        private final ListProperty<String> features;
        private final ListProperty<String> jsonSchemas;
        private final ListProperty<String> libraries;

        public WitExtension(Project project, ObjectFactory objects) {
            this.project = project;
            this.debug = objects.property(Boolean.class).convention(false);
            this.noStdlib = objects.property(Boolean.class).convention(false);
            this.roots = objects.listProperty(String.class).convention(new ArrayList<>());
            this.includes = objects.listProperty(String.class).convention(new ArrayList<>());
            this.features = objects.listProperty(String.class).convention(new ArrayList<>());
            this.jsonSchemas = objects.listProperty(String.class).convention(new ArrayList<>());
            this.libraries = objects.listProperty(String.class).convention(new ArrayList<>());
        }

        public Property<Boolean> getDebug() { return debug; }
        public Property<Boolean> getNoStdlib() { return noStdlib; }
        public ListProperty<String> getRoots() { return roots; }
        public ListProperty<String> getIncludes() { return includes; }
        public ListProperty<String> getFeatures() { return features; }
        public ListProperty<String> getJsonSchemas() { return jsonSchemas; }
        public ListProperty<String> getLibraries() { return libraries; }

        public void root(String path) { roots.add(path); }
        public void include(String path) { includes.add(path); }
        public void feature(String name) { features.add(name); }
        public void json(String path) { jsonSchemas.add(path); }
        public void library(String path) { libraries.add(path); }
        public void noStdlib(boolean value) { noStdlib.set(value); }

        public TaskProvider<WitCodegenTask> codegen(String name, Action<? super WitCodegenTask> configuration) {
            TaskProvider<WitCodegenTask> provider = project.getTasks().register(name, WitCodegenTask.class, task -> {
                task.getSchemaRoots().from(project.provider(() -> mapToFiles(roots.getOrElse(Collections.emptyList()))));
                task.getIncludeRoots().from(project.provider(() -> mapToFiles(includes.getOrElse(Collections.emptyList()))));
                task.getJsonSchemas().from(project.provider(() -> mapToFiles(jsonSchemas.getOrElse(Collections.emptyList()))));
                task.getLibraries().from(project.provider(() -> mapToFiles(libraries.getOrElse(Collections.emptyList()))));
                task.getFeatures().set(features);
                task.getDebug().set(debug);
                task.getNoStdlib().set(noStdlib);
            });
            provider.configure(configuration);
            return provider;
        }

        private List<File> mapToFiles(List<String> paths) {
            List<File> files = new ArrayList<>(paths.size());
            for (String path : paths) {
                files.add(project.file(path));
            }
            return files;
        }
    }

    @Override
    public void apply(@NotNull Project target) {
        target.getExtensions().create("wit", WitExtension.class, target, target.getObjects());
        // Ensure our compiler plugin jars are present on the classpath for all compilations
        target.getConfigurations().matching(c -> c.getName().startsWith("kotlinCompilerPluginClasspath")).configureEach(conf -> {
            target.getDependencies().add(conf.getName(), target.getDependencies().project(Map.of("path", ":wit:compiler-plugin")));
            target.getDependencies().add(
                    conf.getName(),
                    "com.ariawisp.wit:kotlin-wit-parser:0.1-SNAPSHOT"
            );
        });
    }

    @Override
    public boolean isApplicable(@NotNull KotlinCompilation<?> compilation) {
        return true;
    }

    @Override
    public @NotNull Provider<List<SubpluginOption>> applyToCompilation(@NotNull KotlinCompilation<?> kotlinCompilation) {
        Project project = kotlinCompilation.getTarget().getProject();
        WitExtension ext = project.getExtensions().findByType(WitExtension.class);
        return project.provider(() -> {
            if (project.getLogger().isLifecycleEnabled()) {
                project.getLogger().lifecycle(
                        "WIT Gradle plugin evaluating compilation {} – extension present: {}",
                        kotlinCompilation.getCompilationName(),
                        ext != null
                );
            }
            List<SubpluginOption> opts = new ArrayList<>();
            opts.add(new SubpluginOption("enabled", "true"));
            if (ext != null) {
                boolean debugEnabled = Boolean.TRUE.equals(ext.getDebug().getOrElse(false));
                if (debugEnabled) {
                    opts.add(new SubpluginOption("debug", "true"));
                    System.setProperty("wit.debugTrace", "true");
                }
                List<String> roots = unique(ext.getRoots().getOrElse(Collections.emptyList()));
                List<String> includes = unique(ext.getIncludes().getOrElse(Collections.emptyList()));
                List<String> features = unique(ext.getFeatures().getOrElse(Collections.emptyList()));
                List<String> jsonSchemas = unique(ext.getJsonSchemas().getOrElse(Collections.emptyList()));

                if (debugEnabled && project.getLogger().isInfoEnabled()) {
                    project.getLogger().info(
                            "WIT Gradle plugin configured for compilation {} with roots={}, includes={}, features={}, jsonSchemas={}",
                            kotlinCompilation.getCompilationName(),
                            roots,
                            includes,
                            features,
                            jsonSchemas
                    );
                }

                for (String root : roots) {
                    opts.add(new SubpluginOption("root", toAbsolutePath(project, root)));
                }
                for (String include : includes) {
                    opts.add(new SubpluginOption("include", toAbsolutePath(project, include)));
                }
                for (String feature : features) {
                    opts.add(new SubpluginOption("feature", feature));
                }
                for (String schema : jsonSchemas) {
                    opts.add(new SubpluginOption("json", toAbsolutePath(project, schema)));
                }
            }
            else if (project.getLogger().isInfoEnabled()) {
                project.getLogger().info(
                        "WIT Gradle plugin applied to compilation {} but no wit extension was configured.",
                        kotlinCompilation.getCompilationName()
                );
            }
            return opts;
        });
    }

    @Override
    public @NotNull String getCompilerPluginId() {
        return "org.jetbrains.kotlin.wit.compiler";
    }

    @Override
    public @NotNull SubpluginArtifact getPluginArtifact() {
        // Return a benign artifact; real plugin jars are supplied via kotlinCompilerPluginClasspath configurations.
        return new SubpluginArtifact("org.jetbrains.kotlin", "kotlin-compiler-embeddable", null);
    }

    @Override
    public @NotNull SubpluginArtifact getPluginArtifactForNative() {
        return getPluginArtifact();
    }

    private static String toAbsolutePath(Project project, String path) {
        File file = project.file(path);
        return file.getAbsolutePath();
    }

    private static List<String> unique(List<String> values) {
        if (values.isEmpty()) return values;
        Set<String> seen = new LinkedHashSet<>(values);
        return new ArrayList<>(seen);
    }
}
