package app.alertify.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.api.error.ConflictException;
import app.alertify.jpa.specification.InvalidFilterException;

class AiToolFailureTest {

    @Test
    void identifiesWrappedFilterErrorsWithoutExposingFieldOrValue() {
        var failure = AiToolFailure.classify("alertify_configuration_search",
                new IllegalStateException("PRIVATE-OUTER", new InvalidFilterException("PRIVATE-FIELD")));
        assertThat(failure.code()).isEqualTo("TOOL_INVALID_FILTER");
        assertThat(failure.message()).contains("tagId, not tagIds", "OR or AND").doesNotContain("PRIVATE");
        assertThat(AiToolFailure.classify("alertify_alert_tag_search", new InvalidFilterException("PRIVATE")).message())
                .doesNotContain("Configuration", "PRIVATE");
    }

    @Test
    void distinguishesScopedNotFoundFromOtherFailedReads() {
        var missing = AiToolFailure.classify("alertify_pipe_tag_get",
                new IllegalStateException(new ResourceNotFoundException("PRIVATE-NAME")));
        assertThat(missing.code()).isEqualTo("TOOL_RESOURCE_NOT_FOUND");
        assertThat(missing.message()).doesNotContain("PRIVATE");
        var unknown = AiToolFailure.classify("alertify_pipe_tag_get", new IllegalStateException("PRIVATE"));
        assertThat(unknown.code()).isEqualTo("TOOL_EXECUTION_FAILED");
        assertThat(unknown.message()).doesNotContain("PRIVATE");
        var inUse = AiToolFailure.classify("alertify_pipe_tag_delete",
                new IllegalStateException(new ConflictException("PIPE_TAG_IN_USE", "PRIVATE-NAME", java.util.Map.of())));
        assertThat(inUse.code()).isEqualTo("PIPE_TAG_IN_USE");
        assertThat(inUse.message()).contains("No deletion or detachment").doesNotContain("PRIVATE");
    }
}
