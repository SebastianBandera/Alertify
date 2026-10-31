package app.alertify.playwright;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.alerts.model.AlertTemplateParameterDefinition;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.configuration.service.ConfigurationExpressionService;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.jpa.repository.*;
import app.alertify.services.secret.SecretAccessService;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class WizardAlertPlanServiceTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final AlertRepository alerts = mock(AlertRepository.class);
    private final AlertTemplateParameterDefinitionRepository definitions = mock(AlertTemplateParameterDefinitionRepository.class);
    private final ApplicationConfigurationRepository configurations = mock(ApplicationConfigurationRepository.class);
    private final ApplicationSecretRepository secrets = mock(ApplicationSecretRepository.class);
    private final ProcedureRepository procedures = mock(ProcedureRepository.class);
    private final ConfigurationExpressionService expressions = mock(ConfigurationExpressionService.class);
    private final SecretAccessService access = mock(SecretAccessService.class);
    private final WizardAlertPlanService service = new WizardAlertPlanService(alerts, definitions, configurations, secrets, procedures, expressions, access, json);

    private ObjectNode fixture() {
        var template = mock(AlertTemplateDefinition.class);
        when(template.getId()).thenReturn(8L);
        when(template.getTemplateKey()).thenReturn(WizardAlertPlanService.TEMPLATE);
        var alert = mock(Alert.class);
        when(alert.getId()).thenReturn(42L);
        when(alert.getVersion()).thenReturn(3L);
        when(alert.getTemplate()).thenReturn(template);
        when(alerts.findById(42L)).thenReturn(Optional.of(alert));
        var metadata = List.of("url", "steps", "chromiumEnabled", "firefoxEnabled", "webkitEnabled", "loadTimeoutSeconds", "elementTimeoutSeconds").stream().map(key -> {
            var definition = mock(AlertTemplateParameterDefinition.class);
            when(definition.getParameterKey()).thenReturn(key);
            when(definition.getJavaType()).thenReturn(key.endsWith("Seconds") ? "int" : "java.lang.String");
            when(definition.isBindingAllowed()).thenReturn(WizardAlertPlanService.BINDABLE.contains(key));
            when(definition.getAllowedSources()).thenReturn(List.of(AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION, AlertParameterSource.SECRET, AlertParameterSource.PROCEDURE));
            when(definition.getDefaultValue()).thenReturn(switch (key) { case "loadTimeoutSeconds" -> "10"; case "elementTimeoutSeconds" -> "5"; case "chromiumEnabled" -> "true"; case "firefoxEnabled", "webkitEnabled" -> "false"; default -> ""; });
            return definition;
        }).toList();
        when(definitions.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(8L)).thenReturn(metadata);
        var form = json.createObjectNode().put("templateId", 8).put("name", "Unsaved name").put("enabled", true).put("cronExpression", "0 * * * * *");
        var parameters = form.putObject("parameters");
        parameters.set("url", json.valueToTree(Map.of("configured", true, "source", "TEXT", "textValue", "www.example.test")));
        parameters.set("steps", json.valueToTree(Map.of("configured", true, "source", "TEXT", "textValue", "QUERY h1")));
        return json.createObjectNode().put("alertId", 42).put("version", 3).set("form", form);
    }

    @Test
    void importsUnsavedParametersAndReturnFormWithoutUpdatingAnAlertOrResolvingValues() {
        var request = fixture();
        var document = service.create(request);
        assertThat(document.path("settings").path("url").asString()).isEqualTo("https://www.example.test");
        assertThat(document.path("settings").path("steps").asString()).isEqualTo("QUERY h1");
        assertThat(document.path("settings").path("mode").asString()).isEqualTo("ASK");
        assertThat(document.path("sourceAlert").path("form")).isEqualTo(request.path("form"));
        verify(alerts, never()).save(any());
        verifyNoInteractions(expressions, access, procedures);
    }

    @Test
    void importsMetadataOnlyForConfigurationsSecretsAndProceduresAndLocksTheirPlaceholders() {
        var request = fixture();
        var config = mock(ApplicationConfiguration.class);
        when(config.getName()).thenReturn("Private URL");
        when(config.getValueType()).thenReturn(ConfigurationValueType.STRING);
        when(config.getVersion()).thenReturn(4L);
        when(configurations.findById(1L)).thenReturn(Optional.of(config));
        var secret = mock(ApplicationSecret.class);
        when(secret.getName()).thenReturn("Private script");
        when(secret.getValueType()).thenReturn(SecretValueType.STRING);
        when(secrets.findById(2L)).thenReturn(Optional.of(secret));
        var procedure = mock(app.alertify.procedures.model.Procedure.class);
        when(procedure.getName()).thenReturn("Timeout provider");
        when(procedure.isEnabled()).thenReturn(true);
        when(procedures.findById(3L)).thenReturn(Optional.of(procedure));
        var parameters = (ObjectNode) request.path("form").path("parameters");
        parameters.set("url", json.valueToTree(Map.of("configured", true, "source", "CONFIGURATION", "configurationId", 1)));
        parameters.set("steps", json.valueToTree(Map.of("configured", true, "source", "SECRET", "secretId", 2)));
        parameters.set("loadTimeoutSeconds", json.valueToTree(Map.of("configured", true, "source", "PROCEDURE", "procedureId", 3)));
        var document = service.create(request);
        assertThat(document.path("settings").path("url").asString()).isEmpty();
        assertThat(document.path("settings").path("steps").asString()).isEmpty();
        assertThat(document.path("settings").path("loadTimeoutSeconds").asInt()).isEqualTo(10);
        assertThat(document.path("parameterBindings").size()).isEqualTo(3);
        assertThat(document.path("parameterBindings").path("url").path("version").asInt()).isEqualTo(4);
        verifyNoInteractions(expressions, access);
        var settings = json.treeToValue(document.path("settings"), PlaywrightWizardService.Settings.class);
        assertThatThrownBy(() -> WizardAlertPlanService.validateLocked(document, settings.withSteps("QUERY #replacement"))).hasMessageContaining("original resource");
        String before = service.stamp(document);
        when(config.getVersion()).thenReturn(5L);
        assertThat(service.stamp(document)).isNotEqualTo(before);
    }

    @Test
    void rejectsWrongTemplatesStaleAlertsAndMissingOrBinaryResources() {
        var request = fixture();
        request.put("version", 2);
        assertThatThrownBy(() -> service.create(request)).hasMessageContaining("changed");
        request.put("version", 3);
        var template = alerts.findById(42L).orElseThrow().getTemplate();
        when(template.getTemplateKey()).thenReturn("other.Template");
        assertThatThrownBy(() -> service.create(request)).hasMessageContaining("Only Playwright");
        when(template.getTemplateKey()).thenReturn(WizardAlertPlanService.TEMPLATE);
        ((ObjectNode) request.path("form").path("parameters")).set("url", json.valueToTree(Map.of("configured", true, "source", "CONFIGURATION", "configurationId", 1)));
        assertThatThrownBy(() -> service.create(request)).hasMessageContaining("no longer exists");
        var resource = mock(ApplicationConfiguration.class);
        when(resource.getValueType()).thenReturn(ConfigurationValueType.BINARY);
        when(configurations.findById(1L)).thenReturn(Optional.of(resource));
        assertThatThrownBy(() -> service.create(request)).hasMessageContaining("Incompatible");
        verifyNoInteractions(expressions, access);
    }
}
