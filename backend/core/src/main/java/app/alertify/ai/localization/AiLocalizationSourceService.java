package app.alertify.ai.localization;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import app.alertify.ai.localization.AiLocalizationSources.SourceFile;

@Service
public class AiLocalizationSourceService {

    private static final long MAX_TOTAL_BYTES = 448L * 1024;
    private static final Pattern LOCALE = Pattern.compile("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*");
    private static final Path REPOSITORY_ROOT = Path.of("frontend", "src", "app", "core", "i18n");
    private static final Path BACKEND_ROOT = Path.of("..", "frontend", "src", "app", "core", "i18n");

    private final String configuredRoot;

    public AiLocalizationSourceService(@Value("${AI_LOCALIZATION_SOURCE_ROOT:}") String configuredRoot) {
        this.configuredRoot = configuredRoot == null ? "" : configuredRoot.strip();
    }

    public String resolveLocale(String requestedLocale) {
        String locale = requestedLocale == null || requestedLocale.isBlank() ? "en" : requestedLocale.strip();
        if (!LOCALE.matcher(locale).matches())
            throw new IllegalArgumentException("Unsupported frontend locale");

        String fileLocale = locale.toLowerCase(Locale.ROOT);
        Path root = sourceRoot();
        requireFile(root, root.resolve("localization.types.ts"));
        requireFile(root, root.resolve("translations").resolve(fileLocale + ".translations.ts"));
        requireFile(root, root.resolve("translations").resolve(fileLocale + ".extended.translations.ts"));
        return locale;
    }

    public AiLocalizationSources sources(String requestedLocale) {
        String locale = resolveLocale(requestedLocale);
        String fileLocale = locale.toLowerCase(Locale.ROOT);
        Path root = sourceRoot();
        List<Path> files = List.of(
                root.resolve("localization.types.ts"),
                root.resolve("translations").resolve(fileLocale + ".translations.ts"),
                root.resolve("translations").resolve(fileLocale + ".extended.translations.ts"));
        long totalBytes = files.stream().mapToLong(file -> size(root, file)).sum();
        if (totalBytes > MAX_TOTAL_BYTES)
            throw new IllegalStateException("Frontend localization sources exceed the AI tool size limit");

        return new AiLocalizationSources(locale, files.stream().map(file -> new SourceFile(repositoryPath(root, file), read(root, file))).toList());
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

        throw new IllegalStateException("Frontend localization source directory is unavailable");
    }

    private static Path requireFile(Path root, Path file) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot) || !Files.isRegularFile(normalized))
            throw new IllegalArgumentException("Unsupported frontend locale");

        try {
            if (!normalized.toRealPath().startsWith(normalizedRoot.toRealPath()))
                throw new IllegalArgumentException("Unsupported frontend locale");
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unsupported frontend locale");
        }
        return normalized;
    }

    private static long size(Path root, Path file) {
        try {
            return Files.size(requireFile(root, file));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read frontend localization sources", exception);
        }
    }

    private static String read(Path root, Path file) {
        try {
            return Files.readString(requireFile(root, file), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read frontend localization sources", exception);
        }
    }

    private static String repositoryPath(Path root, Path file) {
        return "frontend/src/app/core/i18n/" + root.relativize(file).toString().replace('\\', '/');
    }
}
