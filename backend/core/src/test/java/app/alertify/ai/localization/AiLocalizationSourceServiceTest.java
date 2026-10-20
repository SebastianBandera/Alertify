package app.alertify.ai.localization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiLocalizationSourceServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsTheThreeExactSourcesForEachSelectedLocale() throws IOException {
        write("localization.types.ts", "export type AppLocale = 'en' | 'es-UY';");
        write("translations/en.translations.ts", "export const EN_TRANSLATIONS = { test: 'Test' };");
        write("translations/en.extended.translations.ts", "export const EN_EXTENDED_TRANSLATIONS = {};");
        write("translations/es-uy.translations.ts", "export const ES_UY_TRANSLATIONS = { test: 'Prueba' };");
        write("translations/es-uy.extended.translations.ts", "export const ES_UY_EXTENDED_TRANSLATIONS = {};");
        AiLocalizationSourceService service = new AiLocalizationSourceService(temporaryDirectory.toString());

        AiLocalizationSources sources = service.sources("es-UY");

        assertThat(sources.locale()).isEqualTo("es-UY");
        assertThat(sources.files()).extracting(AiLocalizationSources.SourceFile::path).containsExactly(
                "frontend/src/app/core/i18n/localization.types.ts",
                "frontend/src/app/core/i18n/translations/es-uy.translations.ts",
                "frontend/src/app/core/i18n/translations/es-uy.extended.translations.ts");
        assertThat(sources.files().get(1).content()).contains("Prueba");

        AiLocalizationSources english = service.sources("en");
        assertThat(english.locale()).isEqualTo("en");
        assertThat(english.files()).extracting(AiLocalizationSources.SourceFile::path).containsExactly(
                "frontend/src/app/core/i18n/localization.types.ts",
                "frontend/src/app/core/i18n/translations/en.translations.ts",
                "frontend/src/app/core/i18n/translations/en.extended.translations.ts");
        assertThat(english.files().get(1).content()).contains("Test");
    }

    @Test
    void rejectsUnknownAndUnsafeLocales() throws IOException {
        write("localization.types.ts", "types");
        AiLocalizationSourceService service = new AiLocalizationSourceService(temporaryDirectory.toString());

        assertThatThrownBy(() -> service.resolveLocale("../../secret")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resolveLocale("..\\..\\secret")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resolveLocale("%2e%2e%2fsecret")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resolveLocale("en/../../secret")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resolveLocale("fr-FR")).isInstanceOf(IllegalArgumentException.class);
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
