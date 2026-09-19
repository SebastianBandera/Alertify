package app.alertify.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertExecution;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.jpa.repository.AlertExecutionRepository;
import app.alertify.jpa.repository.AlertParameterValueRepository;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.worker.contract.WorkerCapability;

@ExtendWith(MockitoExtension.class)
class DashboardCardServiceTest {

    @Mock private AlertRepository alertRepository;
    @Mock private AlertParameterValueRepository parameterValueRepository;
    @Mock private AlertExecutionRepository executionRepository;
    @Mock private DashboardExecutionQuery executionQuery;
    @Mock private AlertExecutionRunningRegistry runningRegistry;

    @Test
    void pagesAlertsByIdAndAssemblesTilesFromBatchQueries() {
        Alert ran = alert(5L, "Ran");
        Alert neverRan = alert(6L, "Never ran");
        Instant finishedAt = Instant.parse("2026-09-19T10:00:00Z");
        AlertExecution execution = AlertExecution.result(UUID.randomUUID(), ran, null, AlertExecutionStatus.WARN, finishedAt.minusSeconds(3), finishedAt.minusSeconds(2), finishedAt, null);
        ReflectionTestUtils.setField(execution, "id", 40L);
        DashboardHistorySummaryResponse summary = new DashboardHistorySummaryResponse(AlertExecutionStatus.ERROR, finishedAt.minusSeconds(3_600), finishedAt);
        when(alertRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(ran, neverRan), PageRequest.of(0, 12), 2));
        when(parameterValueRepository.findAllByAlertIdOrdered(anyLong())).thenReturn(List.of());
        when(executionQuery.latestExecutionIds(List.of(5L, 6L))).thenReturn(Map.of(5L, 40L));
        when(executionRepository.findAllById(anyCollection())).thenReturn(List.of(execution));
        when(executionQuery.historySummaries(anyCollection(), any(Instant.class))).thenReturn(Map.of(5L, summary));
        when(runningRegistry.runningSince(5L)).thenReturn(Optional.of(finishedAt.plusSeconds(60)));
        when(runningRegistry.runningSince(6L)).thenReturn(Optional.empty());

        DashboardPageResponse page = service().page(0, 12);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(alertRepository).findAll(pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("id")).isNotNull();
        assertThat(page.page().totalElements()).isEqualTo(2);
        assertThat(page.content()).hasSize(2);
        DashboardCardResponse first = page.content().getFirst();
        assertThat(first.alert().name()).isEqualTo("Ran");
        assertThat(first.lastExecution().status()).isEqualTo(AlertExecutionStatus.WARN);
        assertThat(first.history()).isEqualTo(summary);
        assertThat(first.runningSince()).isEqualTo(finishedAt.plusSeconds(60));
        DashboardCardResponse second = page.content().get(1);
        assertThat(second.lastExecution()).isNull();
        assertThat(second.history()).isNull();
        assertThat(second.runningSince()).isNull();
    }

    @Test
    void clampsPageSizeToTheConfiguredMaximum() {
        when(alertRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, DashboardCardService.MAX_PAGE_SIZE), 0));
        when(executionQuery.latestExecutionIds(List.of())).thenReturn(Map.of());
        when(executionRepository.findAllById(anyCollection())).thenReturn(List.of());
        when(executionQuery.historySummaries(anyCollection(), any(Instant.class))).thenReturn(Map.of());

        service().page(3, 500);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(alertRepository).findAll(pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(DashboardCardService.MAX_PAGE_SIZE);
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(3);
    }

    @Test
    void cardIsEmptyForUnknownAlert() {
        when(alertRepository.findById(9L)).thenReturn(Optional.empty());

        assertThat(service().card(9L)).isEmpty();
    }

    private DashboardCardService service() {
        return new DashboardCardService(alertRepository, parameterValueRepository, executionRepository, executionQuery, runningRegistry);
    }

    private static Alert alert(long id, String name) {
        AlertTemplateDefinition template = new AlertTemplateDefinition(
                "app.alertify.alerts.templates.InternetConnectionAlertTemplate",
                "name.key", "description.key", "source/path.java", WorkerCapability.STANDARD
        );
        ReflectionTestUtils.setField(template, "id", 7L);
        Alert alert = new Alert(template, name, null, "0 0 8 * * *", true, false, Set.of());
        ReflectionTestUtils.setField(alert, "id", id);
        return alert;
    }
}
