package app.alertify.ai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.hooks.model.HookMode;
import app.alertify.hooks.model.HookOutcome;
import app.alertify.hooks.model.HookTargetType;
import app.alertify.pipes.model.PipeOutcome;
import app.alertify.pipes.model.PipeStepPhase;
import app.alertify.pipes.model.PipeStepType;

class AiAdministrativeRequestTest {

    @Test
    void convertsProcedureParametersAndIgnoresUnselectedPlaceholders() {
        var text = new AiProcedureParameterValue("url", AiProcedureParameterSource.TEXT,
                "https://example.test", 0L, 0L, 0L, 0L);
        var pipe = new AiProcedureParameterValue("pipe", AiProcedureParameterSource.PIPE,
                "", 0L, 0L, 0L, 9L);

        var textRequest = text.toRequest();
        var pipeRequest = pipe.toRequest();

        assertThat(textRequest.source()).isEqualTo(AlertParameterSource.TEXT);
        assertThat(textRequest.textValue()).isEqualTo("https://example.test");
        assertThat(textRequest.configurationId()).isNull();
        assertThat(textRequest.secretId()).isNull();
        assertThat(textRequest.procedureId()).isNull();
        assertThat(textRequest.pipeId()).isNull();
        assertThat(textRequest.isSourceSelectionValid()).isTrue();
        assertThat(pipeRequest.source()).isEqualTo(AlertParameterSource.PIPE);
        assertThat(pipeRequest.textValue()).isNull();
        assertThat(pipeRequest.pipeId()).isEqualTo(9L);
        assertThat(pipeRequest.isSourceSelectionValid()).isTrue();
    }

    @Test
    void requiresTheSelectedProcedureParameterReference() {
        var value = new AiProcedureParameterValue("secret", AiProcedureParameterSource.SECRET,
                "", 0L, 0L, 0L, 0L);

        assertThatThrownBy(value::toRequest)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("secretId must be a positive ID for the selected source");
    }

    @Test
    void convertsHookPlaceholdersAndIsoDurations() {
        var target = new AiHookTarget(HookTargetType.ALERT, 7L, Set.of(HookOutcome.SUCCESS), "PT30S");
        var value = new AiHookCreateRequest("hook", null, HookMode.PARALLEL,
                0L, 0, 0, "", List.of(target), Set.of());

        var request = value.toRequest();

        assertThat(request.tokenSecretId()).isNull();
        assertThat(request.maxConcurrentInvocations()).isNull();
        assertThat(request.rateLimitCount()).isNull();
        assertThat(request.rateLimitWindow()).isNull();
        assertThat(request.targets()).singleElement().satisfies(converted ->
                assertThat(converted.busyWaitTimeout()).isEqualTo(Duration.ofSeconds(30)));
    }

    @Test
    void rejectsAnIncompleteHookRateLimit() {
        var value = new AiHookCreateRequest("hook", null, HookMode.PARALLEL,
                0L, 0, 10, "", List.of(), Set.of());

        assertThatThrownBy(value::toRequest)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rateLimitCount and rateLimitWindow must be configured together");
    }

    @Test
    void convertsPipeDefaultsBindingsAndIsoDurations() {
        var binding = new AiPipeBinding("input", "source", "", "/status", "");
        var step = new AiPipeStep("target", PipeStepType.PROCEDURE, null, 11L,
                "PT2S", Set.of(), List.of(binding));
        var value = new AiPipeCreateRequest("pipe", null, false, false,
                "PT1M", List.of(step), Set.of());

        var request = value.toRequest();
        var convertedStep = request.steps().getFirst();
        var convertedBinding = convertedStep.bindings().getFirst();

        assertThat(request.finallyTimeout()).isEqualTo(Duration.ofMinutes(1));
        assertThat(convertedStep.phase()).isEqualTo(PipeStepPhase.MAIN);
        assertThat(convertedStep.timeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(convertedStep.continueOn()).containsExactly(PipeOutcome.SUCCESS);
        assertThat(convertedBinding.sourceOutputKey()).isNull();
        assertThat(convertedBinding.sourceResultPointer()).isEqualTo("/status");
        assertThat(convertedBinding.valueExpression()).isNull();
    }

    @Test
    void normalizesConfigurationExpressionDraftPlaceholders() {
        var value = new AiConfigurationExpressionEvaluationRequest(0L, "", "${env.TEST}");

        var request = value.toRequest();

        assertThat(request.configurationId()).isNull();
        assertThat(request.configurationName()).isNull();
        assertThat(request.expression()).isEqualTo("${env.TEST}");
    }
}
