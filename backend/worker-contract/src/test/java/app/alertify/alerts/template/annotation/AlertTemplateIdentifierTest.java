package app.alertify.alerts.template.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class AlertTemplateIdentifierTest {

    @Test
    void usesTheFullyQualifiedClassNameAsTheTemplateIdentifier() {
        assertThat(AlertTemplateIdentifier.of(SampleTemplate.class))
                .isEqualTo(SampleTemplate.class.getName());
    }

    @Test
    void rejectsClassesThatAreNotAlertTemplates() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlertTemplateIdentifier.of(String.class));
    }

    @AlertTemplate(nameKey = "sample.name", descriptionKey = "sample.description")
    private static final class SampleTemplate {
    }
}
