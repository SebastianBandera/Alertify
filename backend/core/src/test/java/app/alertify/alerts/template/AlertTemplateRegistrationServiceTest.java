package app.alertify.alerts.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import app.alertify.alerts.templates.InternetConnectionAlertTemplate;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;

@ExtendWith(MockitoExtension.class)
class AlertTemplateRegistrationServiceTest {

    @Mock private AlertTemplateDefinitionRepository templateRepository;
    @Mock private AlertTemplateParameterDefinitionRepository parameterRepository;

    @Test
    void discoversAndPersistsTheSharedInternetTemplate() {
        String templateKey = InternetConnectionAlertTemplate.class.getName();
        when(templateRepository.findByTemplateKey(templateKey)).thenReturn(Optional.empty());
        when(parameterRepository.findAllByTemplate_TemplateKey(templateKey)).thenReturn(List.of());
        var service = new AlertTemplateRegistrationService(
            templateRepository, parameterRepository, new DefaultResourceLoader()
        );

        AlertTemplateRegistrationSummary summary = service.scanAndRegister();

        assertEquals(1, summary.templates());
        assertEquals(2, summary.parameters());

        ArgumentCaptor<AlertTemplateDefinition> templateCaptor =
            ArgumentCaptor.forClass(AlertTemplateDefinition.class);
        verify(templateRepository).save(templateCaptor.capture());
        AlertTemplateDefinition template = templateCaptor.getValue();
        assertEquals(templateKey, template.getTemplateKey());
        assertEquals("alerts.template.internet.name", template.getNameKey());

        ArgumentCaptor<AlertTemplateParameterDefinition> parameterCaptor =
            ArgumentCaptor.forClass(AlertTemplateParameterDefinition.class);
        verify(parameterRepository, times(2)).save(parameterCaptor.capture());
        List<AlertTemplateParameterDefinition> parameters = parameterCaptor.getAllValues();

        assertEquals("endpoint", parameters.get(0).getParameterKey());
        assertEquals(String.class.getName(), parameters.get(0).getJavaType());
        assertEquals(List.of("google", "cloudflare"), parameters.get(0).getOptions());
        assertTrue(parameters.get(0).isBindingAllowed());
        assertEquals("google", parameters.get(0).getDefaultValue());

        assertEquals("timeoutSeconds", parameters.get(1).getParameterKey());
        assertEquals(int.class.getName(), parameters.get(1).getJavaType());
        assertEquals(List.of("1", "3", "5", "10"), parameters.get(1).getOptions());
        assertTrue(parameters.get(1).isBindingAllowed());
        assertEquals("3", parameters.get(1).getDefaultValue());
    }
}
