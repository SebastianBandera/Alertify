package app.alertify.alerts.template.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class AlertTemplateKeyTest {

    @Test
    void usesTheFullyQualifiedClassNameAsStableKey() {
        assertThat(AlertTemplateKey.of(SampleTemplate.class))
            .isEqualTo(SampleTemplate.class.getName());
    }

    @Test
    void rejectsClassesWithoutTemplateMetadata() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> AlertTemplateKey.of(String.class));
    }

    @AlertTemplate(nameKey = "sample.name", descriptionKey = "sample.description")
    private static final class SampleTemplate {
    }
}
