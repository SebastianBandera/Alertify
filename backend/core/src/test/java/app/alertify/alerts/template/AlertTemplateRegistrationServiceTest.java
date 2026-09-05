package app.alertify.alerts.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
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
import app.alertify.alerts.templates.devtools.SimulatedLongRunningAlertTemplate;
import app.alertify.alerts.templates.devtools.WritableParameterCopyAlertTemplate;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;

@ExtendWith(MockitoExtension.class)
class AlertTemplateRegistrationServiceTest {

    @Mock private AlertTemplateDefinitionRepository templateRepository;
    @Mock private AlertTemplateParameterDefinitionRepository parameterRepository;

    @Test
    void discoversAndPersistsTheSharedTemplates() {
        String httpsCertificateTemplateKey = HttpsCertificateExpiryAlertTemplate.class.getName();
        String templateKey = InternetConnectionAlertTemplate.class.getName();
        String tcpConnectionTemplateKey = TcpConnectionAlertTemplate.class.getName();
        String webRequestTemplateKey = WebRequestAlertTemplate.class.getName();
        String consoleParameterTemplateKey = ConsoleParameterAlertTemplate.class.getName();
        String devToolsTemplateKey = SimulatedLongRunningAlertTemplate.class.getName();
        String writableParameterCopyTemplateKey = WritableParameterCopyAlertTemplate.class.getName();
        when(templateRepository.findByTemplateKey(httpsCertificateTemplateKey)).thenReturn(Optional.empty());
        when(templateRepository.findByTemplateKey(templateKey)).thenReturn(Optional.empty());
        when(templateRepository.findByTemplateKey(tcpConnectionTemplateKey)).thenReturn(Optional.empty());
        when(templateRepository.findByTemplateKey(webRequestTemplateKey)).thenReturn(Optional.empty());
        when(templateRepository.findByTemplateKey(consoleParameterTemplateKey)).thenReturn(Optional.empty());
        when(templateRepository.findByTemplateKey(devToolsTemplateKey)).thenReturn(Optional.empty());
        when(templateRepository.findByTemplateKey(writableParameterCopyTemplateKey)).thenReturn(Optional.empty());
        when(parameterRepository.findAllByTemplate_TemplateKey(httpsCertificateTemplateKey)).thenReturn(List.of());
        when(parameterRepository.findAllByTemplate_TemplateKey(templateKey)).thenReturn(List.of());
        when(parameterRepository.findAllByTemplate_TemplateKey(tcpConnectionTemplateKey)).thenReturn(List.of());
        when(parameterRepository.findAllByTemplate_TemplateKey(webRequestTemplateKey)).thenReturn(List.of());
        when(parameterRepository.findAllByTemplate_TemplateKey(consoleParameterTemplateKey)).thenReturn(List.of());
        when(parameterRepository.findAllByTemplate_TemplateKey(devToolsTemplateKey)).thenReturn(List.of());
        when(parameterRepository.findAllByTemplate_TemplateKey(writableParameterCopyTemplateKey)).thenReturn(List.of());
        var service = new AlertTemplateRegistrationService(
            templateRepository, parameterRepository, new DefaultResourceLoader()
        );

        AlertTemplateRegistrationSummary summary = service.scanAndRegister();

        assertEquals(7, summary.templates());
        assertEquals(23, summary.parameters());

        ArgumentCaptor<AlertTemplateDefinition> templateCaptor =
            ArgumentCaptor.forClass(AlertTemplateDefinition.class);
        verify(templateRepository, times(7)).save(templateCaptor.capture());
        AlertTemplateDefinition httpsCertificateTemplate = templateCaptor.getAllValues().get(0);
        assertEquals(httpsCertificateTemplateKey, httpsCertificateTemplate.getTemplateKey());
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

        AlertTemplateDefinition template = templateCaptor.getAllValues().get(1);
        assertEquals(templateKey, template.getTemplateKey());
        assertEquals("alerts.template.internet.name", template.getNameKey());
        assertEquals(
            "app/alertify/alerts/templates/InternetConnectionAlertTemplate.java",
            template.getSourcePath()
        );
        assertEquals(1, template.getTags().size());
        assertEquals("alerts.templateTag.network", template.getTags().get(0).nameKey());
        assertEquals("#0EA5E9", template.getTags().get(0).color());

        AlertTemplateDefinition webRequestTemplate = templateCaptor.getAllValues().get(3);
        assertEquals(webRequestTemplateKey, webRequestTemplate.getTemplateKey());
        assertEquals(
            "app/alertify/alerts/templates/WebRequestAlertTemplate.java",
            webRequestTemplate.getSourcePath()
        );

        AlertTemplateDefinition consoleParameterTemplate = templateCaptor.getAllValues().get(4);
        assertEquals(1, consoleParameterTemplate.getTags().size());
        assertEquals("alerts.templateTag.development", consoleParameterTemplate.getTags().get(0).nameKey());
        assertNull(consoleParameterTemplate.getTags().get(0).color());

        ArgumentCaptor<AlertTemplateParameterDefinition> parameterCaptor =
            ArgumentCaptor.forClass(AlertTemplateParameterDefinition.class);
        verify(parameterRepository, times(23)).save(parameterCaptor.capture());
        List<AlertTemplateParameterDefinition> parameters = parameterCaptor.getAllValues();

        assertEquals("endpoint", parameters.get(0).getParameterKey());
        assertEquals(String.class.getName(), parameters.get(0).getJavaType());
        assertEquals(List.of(), parameters.get(0).getOptions());
        assertTrue(parameters.get(0).isBindingAllowed());
        assertNull(parameters.get(0).getDefaultValue());

        assertEquals("warningDays", parameters.get(1).getParameterKey());
        assertEquals(int.class.getName(), parameters.get(1).getJavaType());
        assertEquals(List.of("7", "14", "30", "60", "90"), parameters.get(1).getOptions());
        assertTrue(parameters.get(1).isBindingAllowed());
        assertEquals("30", parameters.get(1).getDefaultValue());

        assertEquals("timeoutSeconds", parameters.get(2).getParameterKey());
        assertEquals(int.class.getName(), parameters.get(2).getJavaType());
        assertEquals(List.of("3", "5", "10", "30"), parameters.get(2).getOptions());
        assertTrue(parameters.get(2).isBindingAllowed());
        assertEquals("10", parameters.get(2).getDefaultValue());

        assertEquals("verifyHostname", parameters.get(3).getParameterKey());
        assertEquals(boolean.class.getName(), parameters.get(3).getJavaType());
        assertEquals(List.of("false", "true"), parameters.get(3).getOptions());
        assertFalse(parameters.get(3).isBindingAllowed());
        assertEquals("true", parameters.get(3).getDefaultValue());

        assertEquals("endpoint", parameters.get(4).getParameterKey());
        assertEquals(String.class.getName(), parameters.get(4).getJavaType());
        assertEquals(List.of("google", "cloudflare"), parameters.get(4).getOptions());
        assertTrue(parameters.get(4).isBindingAllowed());
        assertEquals("google", parameters.get(4).getDefaultValue());

        assertEquals("timeoutSeconds", parameters.get(5).getParameterKey());
        assertEquals(int.class.getName(), parameters.get(5).getJavaType());
        assertEquals(List.of("1", "3", "5", "10"), parameters.get(5).getOptions());
        assertTrue(parameters.get(5).isBindingAllowed());
        assertEquals("3", parameters.get(5).getDefaultValue());

        assertEquals("host", parameters.get(6).getParameterKey());
        assertEquals(String.class.getName(), parameters.get(6).getJavaType());
        assertTrue(parameters.get(6).isBindingAllowed());

        assertEquals("port", parameters.get(7).getParameterKey());
        assertEquals(int.class.getName(), parameters.get(7).getJavaType());
        assertTrue(parameters.get(7).isBindingAllowed());

        assertEquals("timeoutSeconds", parameters.get(8).getParameterKey());
        assertEquals(int.class.getName(), parameters.get(8).getJavaType());
        assertEquals(List.of("1", "3", "5", "10", "30"), parameters.get(8).getOptions());
        assertTrue(parameters.get(8).isBindingAllowed());
        assertEquals("3", parameters.get(8).getDefaultValue());
    }
}
