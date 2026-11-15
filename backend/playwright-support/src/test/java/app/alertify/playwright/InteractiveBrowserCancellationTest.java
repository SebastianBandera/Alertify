package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.worker.wizard.grpc.WizardEvent;
import app.alertify.worker.wizard.grpc.WizardRequest;
import tools.jackson.databind.JsonNode;

class InteractiveBrowserCancellationTest {
    @Test
    @SuppressWarnings("unchecked")
    void closingReleasesProcedureWaitersAndDiscardsLateRepliesAndResults() throws Exception {
        var events = new ArrayList<WizardEvent>();
        var child = new InteractiveBrowserMain(events::add);
        var replies = (ConcurrentMap<String, CompletableFuture<JsonNode>>) ReflectionTestUtils.getField(child, "valueReplies");
        var future = new CompletableFuture<JsonNode>();
        replies.put("pending", future);
        child.close();
        assertThat(future.isCompletedExceptionally()).isTrue();
        assertThat(replies).isEmpty();
        child.procedureReply(WizardRequest.newBuilder().setRequestId("pending").setJson("{\"value\":\"late\"}").build());
        child.handle(WizardRequest.newBuilder().setOperation("OBSERVE").setRequestId("late").setJson("{}").build());
        assertThat(events).isEmpty();
        assertThat(future.isCompletedExceptionally()).isTrue();
    }
}
