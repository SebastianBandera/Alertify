package app.alertify.ai.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.ProcedureTemplateDefinitionRepository;

@Service
public class AiTemplateSourceService {

    private static final long MAX_SOURCE_BYTES = 96L * 1024;
    private static final Path REPOSITORY_ROOT = Path.of("backend", "alert-templates", "src", "main", "java");
    private static final Path BACKEND_ROOT = Path.of("alert-templates", "src", "main", "java");
    private static final String REPOSITORY_PATH_PREFIX = "backend/alert-templates/src/main/java/";

    private final AlertTemplateDefinitionRepository alertTemplates;
    private final ProcedureTemplateDefinitionRepository procedureTemplates;
    private final String configuredRoot;

    public AiTemplateSourceService(AlertTemplateDefinitionRepository alertTemplates,
            ProcedureTemplateDefinitionRepository procedureTemplates,
            @Value("${ALERT_TEMPLATE_SOURCE_ROOT:}") String configuredRoot) {
        this.alertTemplates = alertTemplates;
        this.procedureTemplates = procedureTemplates;
        this.configuredRoot = configuredRoot == null ? "" : configuredRoot.strip();
    }

    public AiTemplateSource alert(String templateKey) {
        var template = alertTemplates.findByTemplateKey(requireTemplateKey(templateKey))
                .orElseThrow(() -> new ResourceNotFoundException("Alert template not found: " + templateKey));
        return source("ALERT", template.getTemplateKey(), template.getSourcePath());
    }

    public AiTemplateSource procedure(String templateKey) {
        var template = procedureTemplates.findByTemplateKey(requireTemplateKey(templateKey))
                .orElseThrow(() -> new ResourceNotFoundException("Procedure template not found: " + templateKey));
        return source("PROCEDURE", template.getTemplateKey(), template.getSourcePath());
    }

    private AiTemplateSource source(String templateType, String templateKey, String sourcePath) {
        Path root = sourceRoot();
        Path relative = validatedRelativePath(sourcePath);
        Path file = requireFile(root, root.resolve(relative));
        try {
            if (Files.size(file) > MAX_SOURCE_BYTES)
                throw new IllegalStateException("Template source exceeds the AI tool size limit");

            return new AiTemplateSource(templateType, templateKey,
                    REPOSITORY_PATH_PREFIX + relative.toString().replace('\\', '/'),
                    Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read template source", exception);
        }
    }

    private Path sourceRoot() {
        if (!configuredRoot.isBlank())
            return Path.of(configuredRoot).toAbsolutePath().normalize();

        Path repository = REPOSITORY_ROOT.toAbsolutePath().normalize();
        if (Files.isDirectory(repository))
            return repository;

        Path backend = BACKEND_ROOT.toAbsolutePath().normalize();
        if (Files.isDirectory(backend))
            return backend;

        throw new IllegalStateException("Template source directory is unavailable");
    }

    private static String requireTemplateKey(String templateKey) {
        if (templateKey == null || templateKey.isBlank())
            throw new IllegalArgumentException("Template key is required");

        return templateKey.strip();
    }

    private static Path validatedRelativePath(String sourcePath) {
        try {
            Path path = Path.of(sourcePath);
            if (path.isAbsolute() || sourcePath.contains("\\") || !sourcePath.endsWith(".java")
                    || !path.equals(path.normalize()) || path.startsWith(".."))
                throw new IllegalStateException("Registered template source path is invalid");

            return path;
        } catch (InvalidPathException | NullPointerException exception) {
            throw new IllegalStateException("Registered template source path is invalid", exception);
        }
    }

    private static Path requireFile(Path root, Path file) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot) || !Files.isRegularFile(normalized))
            throw new ResourceNotFoundException("Template source is unavailable");

        try {
            if (!normalized.toRealPath().startsWith(normalizedRoot.toRealPath()))
                throw new ResourceNotFoundException("Template source is unavailable");
        } catch (IOException exception) {
            throw new ResourceNotFoundException("Template source is unavailable");
        }
        return normalized;
    }
}
