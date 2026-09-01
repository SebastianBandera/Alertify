package app.alertify.worker.runtime;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import app.alertify.alerts.AlertEvaluator;

class AlertTemplateCompiler {

    private final WorkerRuntimeProperties properties;
    private final Map<String, CompiledAlertTemplate> templates = new ConcurrentHashMap<>();

    AlertTemplateCompiler(WorkerRuntimeProperties properties) {
        this.properties = properties;
    }

    boolean isAvailable(String className, String checksum) {
        CompiledAlertTemplate template = templates.get(className);
        return template != null && template.checksum().equals(checksum);
    }

    CompiledAlertTemplate get(String className, String checksum) {
        CompiledAlertTemplate template = templates.get(className);
        if (template == null || !template.checksum().equals(checksum))
            throw new IllegalStateException("Alert template source is not synchronized");
        return template;
    }

    synchronized void synchronize(String className, String checksum, String source) {
        if (isAvailable(className, checksum))
            return;
        if (!SourceVersion.isName(className))
            throw new IllegalArgumentException("className is invalid");
        if (checksum == null || !checksum.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("checksum must be a lowercase SHA-256 value");
        if (source == null || source.isBlank())
            throw new TemplateCompilationException("Alert template source must not be blank");
        if (!checksum.equals(sha256(source)))
            throw new TemplateCompilationException("Alert template source does not match its checksum");

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null)
            throw new TemplateCompilationException("The worker runtime does not include a Java compiler");

        Path outputDirectory = properties.compilerOutputDirectory().resolve(checksum);
        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException exception) {
            throw new TemplateCompilationException("Could not create the compiler output directory", exception);
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(outputDirectory));
            List<Path> classpath = compilerClasspath();
            if (!classpath.isEmpty())
                fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
            JavaFileObject sourceFile = new StringJavaSource(className, source);
            Boolean compiled = compiler.getTask(null, fileManager, diagnostics, List.of("-parameters"), null, List.of(sourceFile)).call();
            if (!Boolean.TRUE.equals(compiled))
                throw new TemplateCompilationException(diagnostics(diagnostics));
        } catch (IOException exception) {
            throw new TemplateCompilationException("Could not compile alert template " + className, exception);
        }

        templates.put(className, load(className, outputDirectory, checksum));
    }

    private CompiledAlertTemplate load(String className, Path outputDirectory, String checksum) {
        try {
            URLClassLoader classLoader = new URLClassLoader(new URL[] { outputDirectory.toUri().toURL() }, AlertEvaluator.class.getClassLoader());
            Class<?> loaded = Class.forName(className, true, classLoader);
            if (!AlertEvaluator.class.isAssignableFrom(loaded)) {
                classLoader.close();
                throw new TemplateCompilationException("Compiled class does not implement AlertEvaluator: " + className);
            }
            return new CompiledAlertTemplate(checksum, loaded.asSubclass(AlertEvaluator.class));
        } catch (IOException | ClassNotFoundException | LinkageError exception) {
            throw new TemplateCompilationException("Could not load compiled alert template " + className, exception);
        }
    }

    private List<Path> compilerClasspath() {
        Path directory = properties.compilerClasspathDirectory();
        if (directory != null && Files.isDirectory(directory)) {
            try (var files = Files.list(directory)) {
                return files.filter(path -> path.getFileName().toString().endsWith(".jar"))
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
            } catch (IOException exception) {
                throw new TemplateCompilationException("Could not read compiler classpath directory", exception);
            }
        }
        String classpath = System.getProperty("java.class.path", "");
        List<Path> entries = new ArrayList<>();
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank())
                entries.add(Path.of(entry));
        }
        return entries;
    }

    private static String diagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder message = new StringBuilder("Alert template compilation failed");
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            message.append(System.lineSeparator())
                    .append("line ").append(diagnostic.getLineNumber())
                    .append(": ").append(diagnostic.getMessage(null));
        }
        return message.toString();
    }

    private static String sha256(String source) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class StringJavaSource extends SimpleJavaFileObject {

        private final String source;

        private StringJavaSource(String className, String source) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
