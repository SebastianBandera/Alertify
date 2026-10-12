package app.alertify.ai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.hooks.model.HookMode;
import app.alertify.hooks.model.HookOutcome;
import app.alertify.hooks.model.HookTargetType;
import app.alertify.pipes.model.PipeOutcome;
import app.alertify.pipes.model.PipeStepPhase;
import app.alertify.pipes.model.PipeStepType;

class AiAdministrativeRequestTest {

    @Test
    void mixedPipeBindingsKeepTheArtifactSourceWhenAnotherExpressionIsEdited() {
        var artifact = new AiPipeBinding("artifact", "source", "backup", null, null);
        var pointer = new AiPipeBinding("input", "source", null, "/status", "{{pipe.VALUE}}");
        var copy = new AiPipeStep("copy", PipeStepType.PROCEDURE, null, 11L, null, Set.of(), List.of(artifact));
        var consumer = new AiPipeStep("consumer", PipeStepType.PROCEDURE, null, 12L, null, Set.of(), List.of(pointer));
        var request = new AiPipeUpdateRequest(3L, "pipe", null, false, false, null, List.of(copy, consumer), Set.of()).toRequest();
        var first = request.steps().getFirst().bindings().getFirst();
        var second = request.steps().getLast().bindings().getFirst();
        assertThat(first.sourceOutputKey()).isEqualTo("backup");
        assertThat(first.sourceResultPointer()).isNull();
        assertThat(first.valueExpression()).isNull();
        assertThat(second.sourceOutputKey()).isNull();
        assertThat(second.sourceResultPointer()).isEqualTo("/status");
        assertThat(second.valueExpression()).isEqualTo("{{pipe.VALUE}}");
    }

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

    @ParameterizedTest
    @EnumSource(value = AiProcedureParameterSource.class, names = "TEXT", mode = EnumSource.Mode.EXCLUDE)
    void requiresEverySelectedProcedureParameterReference(AiProcedureParameterSource source) {
        for (Long invalidId : Arrays.asList(null, 0L, -1L)) {
            var value = new AiProcedureParameterValue("value", source, null, invalidId, invalidId, invalidId, invalidId);
            assertThatThrownBy(value::toRequest)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be a positive ID for the selected source");
        }
    }

    @Test
    void permitsNullInactiveProcedureFieldsButStillRequiresSelectedText() {
        var value = new AiProcedureParameterValue("value", AiProcedureParameterSource.TEXT, "", null, null, null, null);
        assertThat(value.toRequest().textValue()).isEmpty();
        assertThat(value.toRequest().isSourceSelectionValid()).isTrue();
        var missingText = new AiProcedureParameterValue("value", AiProcedureParameterSource.TEXT, null, null, null, null, null);
        assertThatThrownBy(missingText::toRequest).hasMessage("textValue must be supplied when source is TEXT");
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
