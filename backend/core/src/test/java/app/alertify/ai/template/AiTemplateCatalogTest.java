package app.alertify.ai.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.alerts.service.AlertCatalogService;
import app.alertify.procedures.model.ProcedureTemplateDefinition;
import app.alertify.procedures.service.ProcedureCatalogService;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.PipeRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.jpa.repository.ProcedureTemplateDefinitionRepository;
import app.alertify.jpa.repository.ProcedureTemplateOutputDefinitionRepository;
import app.alertify.jpa.repository.ProcedureTemplateParameterDefinitionRepository;
import app.alertify.logging.ApplicationEventLogger;

class AiTemplateCatalogTest {
    @Test
    void alertLookupReadsOnlyTheSelectedDefinitionAndRejectsMismatchedSelectors() {
        var templates = mock(AlertTemplateDefinitionRepository.class);
        var parameters = mock(AlertTemplateParameterDefinitionRepository.class);
        var alerts = mock(AlertRepository.class);
        var service = new AlertCatalogService(templates, parameters, alerts, mock(ApplicationConfigurationRepository.class),
                mock(ApplicationSecretRepository.class), mock(ProcedureRepository.class), mock(ApplicationEventLogger.class));
        var template = mock(AlertTemplateDefinition.class);
        when(template.getId()).thenReturn(12L);
        when(template.getTemplateKey()).thenReturn("synthetic.SqlWatch");
        when(templates.findById(12L)).thenReturn(Optional.of(template));
        when(templates.findByTemplateKey("synthetic.SqlWatch")).thenReturn(Optional.of(template));
        when(parameters.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(12L)).thenReturn(List.of());
        assertThat(service.template(12L, "synthetic.SqlWatch").templateKey()).isEqualTo("synthetic.SqlWatch");
        assertThat(service.template(null, " synthetic.SqlWatch ").id()).isEqualTo(12L);
        assertThatThrownBy(() -> service.template(12L, "synthetic.SqlStatus")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.template(0L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.template(null, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.template(999L, null)).isInstanceOf(app.alertify.api.error.ResourceNotFoundException.class);
        verify(templates, never()).findAll(any(org.springframework.data.domain.Sort.class));
    }

    @Test
    void procedureLookupKeepsOutputsAndTheSameIdentityRules() {
        var templates = mock(ProcedureTemplateDefinitionRepository.class);
        var parameters = mock(ProcedureTemplateParameterDefinitionRepository.class);
        var outputs = mock(ProcedureTemplateOutputDefinitionRepository.class);
        var procedures = mock(ProcedureRepository.class);
        var service = new ProcedureCatalogService(templates, parameters, outputs, procedures,
                mock(ApplicationConfigurationRepository.class), mock(ApplicationSecretRepository.class),
                mock(PipeRepository.class), mock(ApplicationEventLogger.class));
        var template = mock(ProcedureTemplateDefinition.class);
        when(template.getId()).thenReturn(9L);
        when(template.getTemplateKey()).thenReturn("synthetic.CopyFile");
        when(templates.findById(9L)).thenReturn(Optional.of(template));
        when(templates.findByTemplateKey("synthetic.CopyFile")).thenReturn(Optional.of(template));
        when(parameters.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(9L)).thenReturn(List.of());
        when(outputs.findAllByTemplate_IdOrderByOutputOrderAscIdAsc(9L)).thenReturn(List.of());
        assertThat(service.template(9L, null).templateKey()).isEqualTo("synthetic.CopyFile");
        assertThat(service.template(null, "synthetic.CopyFile").outputs()).isEmpty();
        assertThatThrownBy(() -> service.template(9L, "synthetic.Other")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.template(-1L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.template(null, null)).isInstanceOf(IllegalArgumentException.class);
        verify(templates, never()).findAll(any(org.springframework.data.domain.Sort.class));
    }
}
