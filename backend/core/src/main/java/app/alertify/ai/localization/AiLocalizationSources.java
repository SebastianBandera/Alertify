package app.alertify.ai.localization;

import java.util.List;

public record AiLocalizationSources(String locale, List<SourceFile> files) {

    public AiLocalizationSources {
        files = List.copyOf(files);
    }

    public record SourceFile(String path, String content) {
    }
}
