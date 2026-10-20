package app.alertify.ai.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import app.alertify.ai.AiToolExecutor;
import app.alertify.ai.AiToolSupport;
import app.alertify.ai.api.AiAlertStateResponse;
import app.alertify.ai.api.AiPage;
import app.alertify.ai.template.AiTemplateSourceService;
import app.alertify.alerts.api.AlertExecutionResponse;
import app.alertify.alerts.api.AlertResponse;
import app.alertify.alerts.api.AlertStateResponse;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.service.AlertCatalogService;
import app.alertify.alerts.service.AlertCsvService;
import app.alertify.alerts.service.AlertExecutionClosureService;
import app.alertify.alerts.service.AlertExecutionQueryService;
import app.alertify.alerts.service.AlertManagementService;
import app.alertify.alerts.service.AlertTagService;
import app.alertify.alerts.service.ResourceResultObserverService;
import app.alertify.dashboard.DashboardCardResponse;
import app.alertify.dashboard.DashboardCardService;

class AlertAiToolsTest {

    private final AlertManagementService alerts = mock(AlertManagementService.class);
    private final DashboardCardService dashboardCards = mock(DashboardCardService.class);
    private final AlertExecutionQueryService executions = mock(AlertExecutionQueryService.class);
    private final AiToolExecutor executor = mock(AiToolExecutor.class);
    private AlertAiTools tools;

    @BeforeEach
    void setUp() {
        tools = new AlertAiTools(alerts, dashboardCards, mock(AlertCatalogService.class), mock(AlertCsvService.class),
                executions, mock(AlertExecutionClosureService.class),
                mock(AlertTagService.class), mock(ResourceResultObserverService.class), mock(AiTemplateSourceService.class), executor,
                mock(AiToolSupport.class));
    }

    @Test
    void treatsZeroTemplateAsNoFilterAndDefaultsToNewestAlerts() {
        AlertResponse alert = mock(AlertResponse.class);
        when(executor.execute(eq("alertify_alert_search"), org.mockito.ArgumentMatchers.<Supplier<AiPage<AlertResponse>>>any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        when(alerts.search(eq(""), eq(null), eq(Set.of()), eq(false), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(alert), invocation.getArgument(4), 1));

        AiPage<AlertResponse> result = tools.search("", 0L, List.of(), null, null);

        assertEquals(1, result.totalElements());
        var pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(alerts).search(eq(""), eq(null), eq(Set.of()), eq(false), pageable.capture());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(20, pageable.getValue().getPageSize());
        assertEquals(Sort.Direction.DESC, pageable.getValue().getSort().getOrderFor("updatedAt").getDirection());
    }

    @Test
    void returnsPersistedStateTogetherWithTheCompleteDashboardCard() {
        AlertStateResponse persisted = new AlertStateResponse(7L, "status=WARN;failure=unexpectedStatusCode");
        DashboardCardResponse card = mock(DashboardCardResponse.class);
        when(executor.execute(eq("alertify_alert_state"), org.mockito.ArgumentMatchers.<Supplier<AiAlertStateResponse>>any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        when(alerts.state(7L)).thenReturn(persisted);
        when(dashboardCards.card(7L)).thenReturn(Optional.of(card));

        AiAlertStateResponse result = tools.state(7L);

        assertEquals(persisted, result.persistedState());
        assertEquals(card, result.dashboardCard());
    }

    @Test
    void normalizesOptionalExecutionFiltersAndDefaultsToNewestFinishedExecutions() {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-11T23:59:59Z");
        when(executor.execute(eq("alertify_alert_execution_search"), org.mockito.ArgumentMatchers.<Supplier<AiPage<AlertExecutionResponse>>>any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        when(executions.search(eq(7L), eq(null), eq(AlertExecutionStatus.WARN), eq(null), eq(from), eq(to), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(6), 0));

        tools.executionSearch(7L, 0L, AlertExecutionStatus.WARN, "", from, to, null);

        var pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(executions).search(eq(7L), eq(null), eq(AlertExecutionStatus.WARN), eq(null), eq(from), eq(to), pageable.capture());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(20, pageable.getValue().getPageSize());
        assertEquals(Sort.Direction.DESC, pageable.getValue().getSort().getOrderFor("finishedAt").getDirection());
    }
}
