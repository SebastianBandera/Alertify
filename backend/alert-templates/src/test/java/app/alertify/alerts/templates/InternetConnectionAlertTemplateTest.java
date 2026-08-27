package app.alertify.alerts.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateIdentifier;

class InternetConnectionAlertTemplateTest {

    @Test
    void derivesItsIdentifierFromThePackageAndClassName() {
        assertEquals(
            InternetConnectionAlertTemplate.class.getName(),
            AlertTemplateIdentifier.of(InternetConnectionAlertTemplate.class)
        );
    }

    @Test
    void declaresLocalizedTemplateMetadata() {
        AlertTemplate metadata = InternetConnectionAlertTemplate.class.getAnnotation(AlertTemplate.class);

        assertEquals("alerts.template.internet.name", metadata.nameKey());
        assertEquals("alerts.template.internet.description", metadata.descriptionKey());
    }

    @Test
    void declaresEndpointAndTimeoutParameters() throws ReflectiveOperationException {
        AlertParameter endpoint = parameter("endpoint");
        AlertParameter timeout = parameter("timeoutSeconds");

        assertEquals(1, endpoint.order());
        assertTrue(endpoint.bindingAllowed());
        assertEquals("google", endpoint.defaultValue());
        assertEquals(List.of("google", "cloudflare"), List.of(endpoint.options()));

        assertEquals(2, timeout.order());
        assertTrue(timeout.bindingAllowed());
        assertEquals("3", timeout.defaultValue());
        assertEquals(List.of("1", "3", "5", "10"), List.of(timeout.options()));
    }

    private static AlertParameter parameter(String fieldName) throws ReflectiveOperationException {
        Field field = InternetConnectionAlertTemplate.class.getDeclaredField(fieldName);
        return field.getAnnotation(AlertParameter.class);
    }
}
