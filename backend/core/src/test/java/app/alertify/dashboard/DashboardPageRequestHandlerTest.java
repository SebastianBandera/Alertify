package app.alertify.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import app.alertify.alerts.api.AlertExecutionResponse;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.execution.AlertExecutionTrigger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class DashboardPageRequestHandlerTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    @Mock private DashboardCardService cardService;

    @Test
    void usesDefaultsWhenThePayloadIsMissing() {
        DashboardPageResponse response = new DashboardPageResponse(List.of(), new DashboardPageResponse.PageMetadata(12, 0, 0, 0));
        when(cardService.page(0, DashboardCardService.DEFAULT_PAGE_SIZE)).thenReturn(response);

        JsonNode result = handler().handle(null, null);

        assertThat(handler().requestName()).isEqualTo("DASHBOARD_PAGE");
        assertThat(result.get("page").get("size").intValue()).isEqualTo(12);
        assertThat(result.get("content").isArray()).isTrue();
    }

    @Test
    void forwardsPageAndSizeFromThePayload() {
        DashboardPageResponse response = new DashboardPageResponse(List.of(), new DashboardPageResponse.PageMetadata(20, 2, 45, 3));
        when(cardService.page(2, 20)).thenReturn(response);

        JsonNode result = handler().handle(null, jsonMapper.readTree("{\"page\":2,\"size\":20}"));

        verify(cardService).page(2, 20);
        assertThat(result.get("page").get("totalElements").longValue()).isEqualTo(45);
    }

    @Test
    void viewerPagesLeaveTheWorkerAddressOut() {
        AlertExecutionResponse execution = execution();
        DashboardCardResponse card = new DashboardCardResponse(null, execution, execution, null, null, 10);
        DashboardPageResponse response = new DashboardPageResponse(List.of(card), new DashboardPageResponse.PageMetadata(12, 0, 1, 1));
        when(cardService.page(0, DashboardCardService.DEFAULT_PAGE_SIZE)).thenReturn(response);

        JsonNode admin = handler().handle(null, null).get("content").get(0);
        JsonNode viewer = handler().handleForViewer(null).get("content").get(0);

        assertThat(admin.get("lastExecution").get("workerIpAddress").stringValue()).isEqualTo("172.18.0.4");
        for (String field : List.of("lastExecution", "previousIssue")) {
            assertThat(viewer.get(field).get("workerName").stringValue()).isEqualTo("worker-standard-2");
            assertThat(viewer.get(field).path("workerIpAddress").isString()).isFalse();
            assertThat(viewer.get(field).path("workerPort").isNumber()).isFalse();
        }
        assertThat(viewer.toString()).doesNotContain("172.18.0.4").doesNotContain("9090");
    }

    private static AlertExecutionResponse execution() {
        Instant at = Instant.parse("2026-09-24T12:00:00Z");
        return new AlertExecutionResponse(
                1L, UUID.randomUUID(), 16L, "Alert", 2L, "template.name", AlertExecutionStatus.WARN,
                AlertExecutionTrigger.MANUAL, "admin", at, at, at, 0, 0, 0, null, null, null,
                "worker-standard-2", "172.18.0.4", 9090, UUID.randomUUID()
        );
    }

    private DashboardPageRequestHandler handler() {
        return new DashboardPageRequestHandler(cardService, jsonMapper);
    }
}
