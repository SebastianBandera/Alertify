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
import app.alertify.alerts.templates.HttpsCertificateExpiryAlertTemplate;
import app.alertify.alerts.templates.InternetConnectionAlertTemplate;
import app.alertify.alerts.templates.TcpConnectionAlertTemplate;
import app.alertify.alerts.templates.WebRequestAlertTemplate;
import app.alertify.alerts.templates.devtools.ConsoleParameterAlertTemplate;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;

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
        assertTrue(summary.parameters() >= 21);

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

        AlertTemplateDefinition consoleParameterTemplate =
            templatesByKey.get(ConsoleParameterAlertTemplate.class.getName());
        assertNotNull(consoleParameterTemplate);
        assertEquals(1, consoleParameterTemplate.getTags().size());
        assertEquals("alerts.templateTag.development", consoleParameterTemplate.getTags().get(0).nameKey());
        assertNull(consoleParameterTemplate.getTags().get(0).color());

        ArgumentCaptor<AlertTemplateParameterDefinition> parameterCaptor =
            ArgumentCaptor.forClass(AlertTemplateParameterDefinition.class);
        verify(parameterRepository, atLeast(21)).save(parameterCaptor.capture());

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
    }

    private static List<AlertTemplateParameterDefinition> parametersOf(
        ArgumentCaptor<AlertTemplateParameterDefinition> captor, AlertTemplateDefinition template
    ) {
        return captor.getAllValues().stream()
            .filter(parameter -> parameter.getTemplate() == template)
            .toList();
    }
}
