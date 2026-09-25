package app.alertify.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.logging.ApplicationEventLogger;

@ExtendWith(MockitoExtension.class)
class AlertIssueAcknowledgementServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private AlertRepository alertRepository;
    @Mock private ApplicationEventLogger eventLogger;

    @Test
    void acknowledgingStoresTheCallersOwnInstantForTheAlert() {
        Instant stored = Instant.parse("2026-09-24T12:00:00Z");
        when(alertRepository.existsById(16L)).thenReturn(true);
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), eq(16L), eq("user-subject"), any(OffsetDateTime.class)))
                .thenReturn(stored);

        Instant before = Instant.now();
        AlertIssueAcknowledgementResponse response = service().acknowledge(16L, "user-subject");

        ArgumentCaptor<OffsetDateTime> acknowledgedAt = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), any(RowMapper.class), eq(16L), eq("user-subject"), acknowledgedAt.capture());
        assertThat(sql.getValue()).contains("on conflict (alert_id, user_subject) do update");
        assertThat(acknowledgedAt.getValue().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(acknowledgedAt.getValue().toInstant()).isBetween(before, Instant.now());
        assertThat(response).isEqualTo(new AlertIssueAcknowledgementResponse(16L, stored));
        verify(eventLogger).successAfterCommit(eq("ALERT_ISSUES_ACKNOWLEDGED"), anyMap());
    }

    @Test
    void acknowledgingAnUnknownAlertIsNotFoundAndStoresNothing() {
        when(alertRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service().acknowledge(99L, "user-subject"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(jdbcTemplate, never()).queryForObject(anyString(), any(RowMapper.class), any(), any(), any());
    }

    @Test
    void listsOnlyTheCallersAcknowledgements() {
        service().forUser("user-subject");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), eq("user-subject"));
        assertThat(sql.getValue()).contains("where user_subject = ?");
    }

    private AlertIssueAcknowledgementService service() {
        return new AlertIssueAcknowledgementService(jdbcTemplate, alertRepository, eventLogger);
    }
}
