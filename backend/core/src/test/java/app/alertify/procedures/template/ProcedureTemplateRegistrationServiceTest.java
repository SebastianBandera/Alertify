package app.alertify.procedures.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
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

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.jpa.repository.ProcedureTemplateDefinitionRepository;
import app.alertify.jpa.repository.ProcedureTemplateParameterDefinitionRepository;
import app.alertify.procedures.model.ProcedureTemplateDefinition;
import app.alertify.procedures.model.ProcedureTemplateParameterDefinition;
import app.alertify.procedures.templates.TotpProcedureTemplate;
import app.alertify.procedures.templates.devtools.ConsoleParameterProcedureTemplate;
import app.alertify.procedures.templates.devtools.SimulatedLongRunningProcedureTemplate;
import app.alertify.procedures.templates.devtools.WritableParameterCopyProcedureTemplate;

@ExtendWith(MockitoExtension.class)
class ProcedureTemplateRegistrationServiceTest {
    @Mock private ProcedureTemplateDefinitionRepository templateRepository;
    @Mock private ProcedureTemplateParameterDefinitionRepository parameterRepository;

    @Test
    void registersProcedureTemplatesAndTheirSourceRestrictions() {
        when(templateRepository.findByTemplateKey(anyString())).thenReturn(Optional.empty());
        when(parameterRepository.findAllByTemplate_TemplateKey(anyString())).thenReturn(List.of());
        ProcedureTemplateRegistrationService service = new ProcedureTemplateRegistrationService(
                templateRepository, parameterRepository, new DefaultResourceLoader()
        );

        assertThat(service.scanAndRegister()).isEqualTo(4);

        ArgumentCaptor<ProcedureTemplateDefinition> template = ArgumentCaptor.captor();
        verify(templateRepository, org.mockito.Mockito.times(4)).save(template.capture());
        ProcedureTemplateDefinition totp = template.getAllValues().stream()
                .filter(value -> value.getTemplateKey().equals(TotpProcedureTemplate.class.getName()))
                .findFirst().orElseThrow();
        assertThat(totp.isSensitiveResult()).isTrue();
        assertThat(totp.getTags()).singleElement().satisfies(tag ->
                assertThat(tag.nameKey()).isEqualTo("procedures.templateTag.security"));

        assertThat(template.getAllValues()).extracting(ProcedureTemplateDefinition::getTemplateKey)
                .contains(ConsoleParameterProcedureTemplate.class.getName(),
                        WritableParameterCopyProcedureTemplate.class.getName(),
                        SimulatedLongRunningProcedureTemplate.class.getName());

        ArgumentCaptor<ProcedureTemplateParameterDefinition> parameters = ArgumentCaptor.captor();
        verify(parameterRepository, org.mockito.Mockito.times(11)).save(parameters.capture());
        List<ProcedureTemplateParameterDefinition> totpParameters = parameters.getAllValues().stream()
                .filter(value -> value.getTemplate() == totp).toList();
        assertThat(totpParameters.getFirst().getParameterKey()).isEqualTo("secret");
        assertThat(totpParameters.getFirst().getAllowedSources())
                .containsExactly(AlertParameterSource.SECRET);
        assertThat(totpParameters.get(1).getOptions()).containsExactly("SHA1", "SHA256", "SHA512");
    }
}
