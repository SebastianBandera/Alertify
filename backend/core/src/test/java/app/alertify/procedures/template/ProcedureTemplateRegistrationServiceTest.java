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

@ExtendWith(MockitoExtension.class)
class ProcedureTemplateRegistrationServiceTest {
    @Mock private ProcedureTemplateDefinitionRepository templateRepository;
    @Mock private ProcedureTemplateParameterDefinitionRepository parameterRepository;

    @Test
    void registersTheSensitiveTotpTemplateAndItsSourceRestrictions() {
        when(templateRepository.findByTemplateKey(anyString())).thenReturn(Optional.empty());
        when(parameterRepository.findAllByTemplate_TemplateKey(anyString())).thenReturn(List.of());
        ProcedureTemplateRegistrationService service = new ProcedureTemplateRegistrationService(
                templateRepository, parameterRepository, new DefaultResourceLoader()
        );

        assertThat(service.scanAndRegister()).isEqualTo(1);

        ArgumentCaptor<ProcedureTemplateDefinition> template = ArgumentCaptor.captor();
        verify(templateRepository).save(template.capture());
        assertThat(template.getValue().getTemplateKey()).isEqualTo(TotpProcedureTemplate.class.getName());
        assertThat(template.getValue().isSensitiveResult()).isTrue();
        assertThat(template.getValue().getTags()).singleElement().satisfies(tag ->
                assertThat(tag.nameKey()).isEqualTo("procedures.templateTag.security"));

        ArgumentCaptor<ProcedureTemplateParameterDefinition> parameters = ArgumentCaptor.captor();
        verify(parameterRepository, org.mockito.Mockito.times(4)).save(parameters.capture());
        assertThat(parameters.getAllValues().getFirst().getParameterKey()).isEqualTo("secret");
        assertThat(parameters.getAllValues().getFirst().getAllowedSources())
                .containsExactly(AlertParameterSource.SECRET);
        assertThat(parameters.getAllValues().get(1).getOptions()).containsExactly("SHA1", "SHA256", "SHA512");
    }
}
