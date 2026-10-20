package app.alertify.ai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertParameterValue;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.alerts.model.AlertTemplateParameterDefinition;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.procedures.model.Procedure;
import app.alertify.procedures.model.ProcedureParameterValue;
import app.alertify.procedures.model.ProcedureTemplateDefinition;
import app.alertify.procedures.model.ProcedureTemplateParameterDefinition;

class ParameterLiteralBindingContractTest {

    @Test
    void alertLiteralOptionsRemainValidWhenReferenceBindingsAreForbidden() {
        var definition = mock(AlertTemplateParameterDefinition.class);
        var template = mock(AlertTemplateDefinition.class);
        var owner = mock(Alert.class);
        when(template.getTemplateKey()).thenReturn("synthetic.AlertLiterals");
        when(owner.getTemplate()).thenReturn(template);
        when(definition.getTemplate()).thenReturn(template);
        when(definition.isBindingAllowed()).thenReturn(false);
        when(definition.getParameterKey()).thenReturn("showDetailsInJson");
        when(definition.getJavaType()).thenReturn("boolean");
        when(definition.getOptions()).thenReturn(List.of("false", "true"));

        var value = AlertParameterValue.text(owner, definition, "false");

        assertThat(value.getSource()).isEqualTo(AlertParameterSource.TEXT);
        assertThat(value.getTextValue()).isEqualTo("false");
        value.replaceWithText("true");
        assertThatThrownBy(() -> value.replaceWithText("invalid")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value.replaceWithConfiguration(mock(ApplicationConfiguration.class)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("binding is not allowed");
        assertThat(value.getSource()).isEqualTo(AlertParameterSource.TEXT);
        assertThat(value.getTextValue()).isEqualTo("true");
    }

    @Test
    void procedureLiteralOptionsStillRequireTheTextSourceAndRejectReferences() {
        var definition = mock(ProcedureTemplateParameterDefinition.class);
        var template = mock(ProcedureTemplateDefinition.class);
        var owner = mock(Procedure.class);
        when(template.getTemplateKey()).thenReturn("synthetic.ProcedureLiterals");
        when(owner.getTemplate()).thenReturn(template);
        when(definition.getTemplate()).thenReturn(template);
        when(definition.isBindingAllowed()).thenReturn(false);
        when(definition.getParameterKey()).thenReturn("enabled");
        when(definition.getOptions()).thenReturn(List.of("false", "true"));
        when(definition.getAllowedSources()).thenReturn(List.of(AlertParameterSource.TEXT));

        var value = ProcedureParameterValue.text(owner, definition, "false");

        assertThat(value.getSource()).isEqualTo(AlertParameterSource.TEXT);
        assertThat(value.getTextValue()).isEqualTo("false");
        assertThatThrownBy(() -> value.replaceWithText("invalid")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value.replaceWithConfiguration(mock(ApplicationConfiguration.class)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("binding is not allowed");
        when(definition.getAllowedSources()).thenReturn(List.of(AlertParameterSource.SECRET));
        assertThatThrownBy(() -> value.replaceWithText("true"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source TEXT is not allowed");
        assertThat(value.getSource()).isEqualTo(AlertParameterSource.TEXT);
        assertThat(value.getTextValue()).isEqualTo("false");
    }
}
