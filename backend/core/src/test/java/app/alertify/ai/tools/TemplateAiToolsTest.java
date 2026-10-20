package app.alertify.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import app.alertify.ai.AiToolExecutor;
import app.alertify.ai.template.AiTemplateDetails;
import app.alertify.ai.template.AiTemplateDetailsService;
import app.alertify.ai.template.AiTemplateBindingCandidates;
import app.alertify.ai.template.AiTemplateParameterValidation;
import app.alertify.ai.template.AiTemplateParametersService;

class TemplateAiToolsTest {
    @Test
    void validationUsesTheAuditedReadOnlyExecutor() {
        var service = mock(AiTemplateParametersService.class);
        var executor = mock(AiToolExecutor.class);
        var result = mock(AiTemplateParameterValidation.class);
        when(executor.execute(eq(TemplateAiTools.VALIDATE_TOOL), org.mockito.ArgumentMatchers.<Supplier<AiTemplateParameterValidation>>any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        when(service.validate("test.Template", java.util.List.of())).thenReturn(result);

        assertThat(new TemplateAiTools(mock(AiTemplateDetailsService.class), executor, service).validate("test.Template", java.util.List.of())).isSameAs(result);
        verify(service).validate("test.Template", java.util.List.of());
    }

    @Test
    void candidatesUseTheAuditedReadOnlyExecutor() {
        var service = mock(AiTemplateParametersService.class);
        var executor = mock(AiToolExecutor.class);
        var result = mock(AiTemplateBindingCandidates.class);
        when(executor.execute(eq(TemplateAiTools.CANDIDATES_TOOL), org.mockito.ArgumentMatchers.<Supplier<AiTemplateBindingCandidates>>any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        when(service.candidates("test.Template", "credentials", null, null)).thenReturn(result);

        assertThat(new TemplateAiTools(mock(AiTemplateDetailsService.class), executor, service).candidates("test.Template", "credentials", null, null)).isSameAs(result);
        verify(service).candidates("test.Template", "credentials", null, null);
    }

    @Test
    void readsTheSelectedReferenceThroughTheAuditedExecutor() {
        var service = mock(AiTemplateDetailsService.class);
        var executor = mock(AiToolExecutor.class);
        var details = mock(AiTemplateDetails.class);
        String key = "app.alertify.alerts.templates.KubernetesKubeProxyCertificateExpiryAlertTemplate";
        when(executor.execute(eq(TemplateAiTools.TOOL_NAME), org.mockito.ArgumentMatchers.<Supplier<AiTemplateDetails>>any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        when(service.details(key)).thenReturn(details);

        assertThat(new TemplateAiTools(service, executor, mock(app.alertify.ai.template.AiTemplateParametersService.class)).details(key)).isSameAs(details);

        verify(executor).execute(eq(TemplateAiTools.TOOL_NAME), any(Supplier.class));
        verify(service).details(key);
    }
}
