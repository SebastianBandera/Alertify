package app.alertify.alerts.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;

import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.alerts.model.AlertTemplateParameterDefinition;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.alerts.templates.HttpsCertificateExpiryAlertTemplate;
import app.alertify.alerts.templates.InternetConnectionAlertTemplate;
import app.alertify.alerts.templates.PlaywrightPageAlertTemplate;
import app.alertify.alerts.templates.SqlStatusAlertTemplate;
import app.alertify.alerts.templates.SqlThresholdAlertTemplate;
import app.alertify.alerts.templates.SqlWatchAlertTemplate;
import app.alertify.alerts.templates.TcpConnectionAlertTemplate;
import app.alertify.alerts.templates.WebRequestAlertTemplate;
import app.alertify.alerts.templates.devtools.SimulatedLongRunningPlaywrightAlertTemplate;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;
import app.alertify.worker.contract.WorkerCapability;

@ExtendWith(MockitoExtension.class)
class AlertTemplateRegistrationServiceTest {

    @Mock private AlertTemplateDefinitionRepository templateRepository;
    @Mock private AlertTemplateParameterDefinitionRepository parameterRepository;

    /**
     * Only asserts on the templates tracked in this repository, looked up by
     * template key rather than by position or exact total count. The scanned
     * base package also allows an optional, gitignored
     * {@code app.alertify.alerts.templates.custom} folder for a developer's
     * own local example templates - whatever exists there on a given machine
     * must never affect this test.
     */
    @Test
    void discoversAndPersistsTheSharedTemplates() {
        when(templateRepository.findByTemplateKey(anyString())).thenReturn(Optional.empty());
        when(parameterRepository.findAllByTemplate_TemplateKey(anyString())).thenReturn(List.of());
        var service = new AlertTemplateRegistrationService(
            templateRepository, parameterRepository, new DefaultResourceLoader()
        );

        AlertTemplateRegistrationSummary summary = service.scanAndRegister();

        assertTrue(summary.templates() >= 7);
        assertTrue(summary.parameters() >= 31);

        ArgumentCaptor<AlertTemplateDefinition> templateCaptor =
            ArgumentCaptor.forClass(AlertTemplateDefinition.class);
        verify(templateRepository, atLeast(7)).save(templateCaptor.capture());
        Map<String, AlertTemplateDefinition> templatesByKey = new LinkedHashMap<>();
        for (AlertTemplateDefinition template : templateCaptor.getAllValues())
            templatesByKey.put(template.getTemplateKey(), template);

        AlertTemplateDefinition httpsCertificateTemplate =
            templatesByKey.get(HttpsCertificateExpiryAlertTemplate.class.getName());
        assertNotNull(httpsCertificateTemplate);
        assertEquals("alerts.template.httpsCertificate.name", httpsCertificateTemplate.getNameKey());
        assertEquals(
            "app/alertify/alerts/templates/HttpsCertificateExpiryAlertTemplate.java",
            httpsCertificateTemplate.getSourcePath()
        );
        assertEquals(2, httpsCertificateTemplate.getTags().size());
        assertEquals("alerts.templateTag.network", httpsCertificateTemplate.getTags().get(0).nameKey());
        assertEquals("#0EA5E9", httpsCertificateTemplate.getTags().get(0).color());
        assertEquals("alerts.templateTag.security", httpsCertificateTemplate.getTags().get(1).nameKey());
        assertEquals("#7C3AED", httpsCertificateTemplate.getTags().get(1).color());

        AlertTemplateDefinition internetTemplate =
            templatesByKey.get(InternetConnectionAlertTemplate.class.getName());
        assertNotNull(internetTemplate);
        assertEquals("alerts.template.internet.name", internetTemplate.getNameKey());
        assertEquals(
            "app/alertify/alerts/templates/InternetConnectionAlertTemplate.java",
            internetTemplate.getSourcePath()
        );
        assertEquals(1, internetTemplate.getTags().size());
        assertEquals("alerts.templateTag.network", internetTemplate.getTags().get(0).nameKey());
        assertEquals("#0EA5E9", internetTemplate.getTags().get(0).color());

        AlertTemplateDefinition tcpTemplate = templatesByKey.get(TcpConnectionAlertTemplate.class.getName());
        assertNotNull(tcpTemplate);

        AlertTemplateDefinition webRequestTemplate =
            templatesByKey.get(WebRequestAlertTemplate.class.getName());
        assertNotNull(webRequestTemplate);
        assertEquals(
            "app/alertify/alerts/templates/WebRequestAlertTemplate.java",
            webRequestTemplate.getSourcePath()
        );

        AlertTemplateDefinition playwrightPageTemplate =
            templatesByKey.get(PlaywrightPageAlertTemplate.class.getName());
        assertNotNull(playwrightPageTemplate);
        assertEquals(WorkerCapability.PLAYWRIGHT, playwrightPageTemplate.getRequiredCapability());
        assertEquals(
            "app/alertify/alerts/templates/PlaywrightPageAlertTemplate.java",
            playwrightPageTemplate.getSourcePath()
        );

        AlertTemplateDefinition simulatedPlaywrightTemplate =
            templatesByKey.get(SimulatedLongRunningPlaywrightAlertTemplate.class.getName());
        assertNotNull(simulatedPlaywrightTemplate);
        assertEquals(WorkerCapability.PLAYWRIGHT, simulatedPlaywrightTemplate.getRequiredCapability());
        assertEquals(
            "app/alertify/alerts/templates/devtools/SimulatedLongRunningPlaywrightAlertTemplate.java",
            simulatedPlaywrightTemplate.getSourcePath()
        );

        AlertTemplateDefinition sqlThresholdTemplate =
            templatesByKey.get(SqlThresholdAlertTemplate.class.getName());
        assertNotNull(sqlThresholdTemplate);
        assertEquals("alerts.template.sqlThreshold.name", sqlThresholdTemplate.getNameKey());
        assertEquals(
            "app/alertify/alerts/templates/SqlThresholdAlertTemplate.java",
            sqlThresholdTemplate.getSourcePath()
        );

        AlertTemplateDefinition sqlStatusTemplate =
            templatesByKey.get(SqlStatusAlertTemplate.class.getName());
        assertNotNull(sqlStatusTemplate);
        assertEquals("alerts.template.sqlStatus.name", sqlStatusTemplate.getNameKey());
        assertEquals(
            "app/alertify/alerts/templates/SqlStatusAlertTemplate.java",
            sqlStatusTemplate.getSourcePath()
        );

        ArgumentCaptor<AlertTemplateParameterDefinition> parameterCaptor =
            ArgumentCaptor.forClass(AlertTemplateParameterDefinition.class);
        verify(parameterRepository, atLeast(31)).save(parameterCaptor.capture());

        List<AlertTemplateParameterDefinition> httpsParameters = parametersOf(parameterCaptor, httpsCertificateTemplate);
        assertEquals(4, httpsParameters.size());
        assertEquals("endpoint", httpsParameters.get(0).getParameterKey());
        assertEquals(String.class.getName(), httpsParameters.get(0).getJavaType());
        assertEquals(List.of(), httpsParameters.get(0).getOptions());
        assertTrue(httpsParameters.get(0).isBindingAllowed());
        assertNull(httpsParameters.get(0).getDefaultValue());

        assertEquals("warningDays", httpsParameters.get(1).getParameterKey());
        assertEquals(int.class.getName(), httpsParameters.get(1).getJavaType());
        assertEquals(List.of("7", "14", "30", "60", "90"), httpsParameters.get(1).getOptions());
        assertTrue(httpsParameters.get(1).isBindingAllowed());
        assertEquals("30", httpsParameters.get(1).getDefaultValue());

        assertEquals("timeoutSeconds", httpsParameters.get(2).getParameterKey());
        assertEquals(int.class.getName(), httpsParameters.get(2).getJavaType());
        assertEquals(List.of("3", "5", "10", "30"), httpsParameters.get(2).getOptions());
        assertTrue(httpsParameters.get(2).isBindingAllowed());
        assertEquals("10", httpsParameters.get(2).getDefaultValue());

        assertEquals("verifyHostname", httpsParameters.get(3).getParameterKey());
        assertEquals(boolean.class.getName(), httpsParameters.get(3).getJavaType());
        assertEquals(List.of("false", "true"), httpsParameters.get(3).getOptions());
        assertFalse(httpsParameters.get(3).isBindingAllowed());
        assertEquals("true", httpsParameters.get(3).getDefaultValue());

        List<AlertTemplateParameterDefinition> internetParameters = parametersOf(parameterCaptor, internetTemplate);
        assertEquals(2, internetParameters.size());
        assertEquals("endpoint", internetParameters.get(0).getParameterKey());
        assertEquals(String.class.getName(), internetParameters.get(0).getJavaType());
        assertEquals(List.of("google", "cloudflare"), internetParameters.get(0).getOptions());
        assertTrue(internetParameters.get(0).isBindingAllowed());
        assertEquals("google", internetParameters.get(0).getDefaultValue());

        assertEquals("timeoutSeconds", internetParameters.get(1).getParameterKey());
        assertEquals(int.class.getName(), internetParameters.get(1).getJavaType());
        assertEquals(List.of("1", "3", "5", "10"), internetParameters.get(1).getOptions());
        assertTrue(internetParameters.get(1).isBindingAllowed());
        assertEquals("3", internetParameters.get(1).getDefaultValue());

        List<AlertTemplateParameterDefinition> simulatedPlaywrightParameters = parametersOf(parameterCaptor, simulatedPlaywrightTemplate);
        assertEquals(4, simulatedPlaywrightParameters.size());
        assertEquals("sleepMilliseconds", simulatedPlaywrightParameters.get(0).getParameterKey());
        assertEquals("10000", simulatedPlaywrightParameters.get(0).getDefaultValue());
        assertEquals("randomInitialDelayEnabled", simulatedPlaywrightParameters.get(1).getParameterKey());
        assertFalse(simulatedPlaywrightParameters.get(1).isBindingAllowed());

        List<AlertTemplateParameterDefinition> playwrightPageParameters = parametersOf(parameterCaptor, playwrightPageTemplate);
        assertEquals(7, playwrightPageParameters.size());
        assertEquals("url", playwrightPageParameters.get(0).getParameterKey());
        assertEquals("chromiumEnabled", playwrightPageParameters.get(1).getParameterKey());
        assertEquals("true", playwrightPageParameters.get(1).getDefaultValue());
        assertFalse(playwrightPageParameters.get(1).isBindingAllowed());
        assertEquals("firefoxEnabled", playwrightPageParameters.get(2).getParameterKey());
        assertEquals("false", playwrightPageParameters.get(2).getDefaultValue());
        assertEquals("webkitEnabled", playwrightPageParameters.get(3).getParameterKey());
        assertEquals("false", playwrightPageParameters.get(3).getDefaultValue());
        assertEquals("loadTimeoutSeconds", playwrightPageParameters.get(4).getParameterKey());
        assertEquals("10", playwrightPageParameters.get(4).getDefaultValue());
        assertEquals("elementTimeoutSeconds", playwrightPageParameters.get(5).getParameterKey());
        assertEquals("5", playwrightPageParameters.get(5).getDefaultValue());
        assertEquals("steps", playwrightPageParameters.get(6).getParameterKey());
        assertTrue(playwrightPageParameters.get(6).isMultiline());
        assertFalse(playwrightPageParameters.get(6).isRequired());

        List<AlertTemplateParameterDefinition> tcpParameters = parametersOf(parameterCaptor, tcpTemplate);
        assertEquals(3, tcpParameters.size());
        assertEquals("host", tcpParameters.get(0).getParameterKey());
        assertEquals(String.class.getName(), tcpParameters.get(0).getJavaType());
        assertTrue(tcpParameters.get(0).isBindingAllowed());

        assertEquals("port", tcpParameters.get(1).getParameterKey());
        assertEquals(int.class.getName(), tcpParameters.get(1).getJavaType());
        assertTrue(tcpParameters.get(1).isBindingAllowed());

        assertEquals("timeoutSeconds", tcpParameters.get(2).getParameterKey());
        assertEquals(int.class.getName(), tcpParameters.get(2).getJavaType());
        assertEquals(List.of("1", "3", "5", "10", "30"), tcpParameters.get(2).getOptions());
        assertTrue(tcpParameters.get(2).isBindingAllowed());
        assertEquals("3", tcpParameters.get(2).getDefaultValue());

        List<AlertTemplateParameterDefinition> sqlThresholdParameters = parametersOf(parameterCaptor, sqlThresholdTemplate);
        assertEquals(9, sqlThresholdParameters.size());
        assertEquals("credentials", sqlThresholdParameters.get(0).getParameterKey());
        assertEquals(List.of(AlertParameterSource.SECRET), sqlThresholdParameters.get(0).getAllowedSources());
        assertEquals(List.of("DB_SECRET"), sqlThresholdParameters.get(0).getAllowedSecretValueTypes());
        assertEquals("numericColumnName", sqlThresholdParameters.get(3).getParameterKey());
        assertEquals("detailColumnName", sqlThresholdParameters.get(4).getParameterKey());
        assertFalse(sqlThresholdParameters.get(4).isRequired());
        assertEquals("threshold", sqlThresholdParameters.get(5).getParameterKey());
        assertEquals(long.class.getName(), sqlThresholdParameters.get(5).getJavaType());
        assertEquals("thresholdType", sqlThresholdParameters.get(6).getParameterKey());
        assertFalse(sqlThresholdParameters.get(6).isBindingAllowed());
        assertEquals("warn_if_bigger", sqlThresholdParameters.get(6).getDefaultValue());

        AlertTemplateDefinition sqlWatchTemplate = templatesByKey.get(SqlWatchAlertTemplate.class.getName());
        assertNotNull(sqlWatchTemplate);
        List<AlertTemplateParameterDefinition> sqlWatchParameters = parametersOf(parameterCaptor, sqlWatchTemplate);
        assertEquals(8, sqlWatchParameters.size());
        AlertTemplateParameterDefinition snapshot = sqlWatchParameters.get(4);
        assertEquals("snapshot", snapshot.getParameterKey());
        assertTrue(snapshot.isWritableBindingRequired());
        assertEquals(List.of(AlertParameterSource.CONFIGURATION, AlertParameterSource.SECRET), snapshot.getAllowedSources());
        assertEquals(List.of("BINARY"), snapshot.getAllowedConfigurationValueTypes());
        assertEquals(List.of("BINARY"), snapshot.getAllowedSecretValueTypes());
    }

    private static List<AlertTemplateParameterDefinition> parametersOf(
        ArgumentCaptor<AlertTemplateParameterDefinition> captor, AlertTemplateDefinition template
    ) {
        return captor.getAllValues().stream()
            .filter(parameter -> parameter.getTemplate() == template)
            .toList();
    }
}
