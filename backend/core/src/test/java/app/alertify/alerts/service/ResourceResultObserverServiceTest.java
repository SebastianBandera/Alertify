package app.alertify.alerts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.api.error.InvalidAlertRequestException;
import tools.jackson.databind.json.JsonMapper;

class ResourceResultObserverServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ResourceResultObserverService service = new ResourceResultObserverService(jdbc, JsonMapper.builder().build());

    @Test
    void observesEachResourceTerminalSeverityWithoutInvokingIt() throws Exception {
        for (String kind : List.of("PIPE", "PROCEDURE", "HOOK")) {
            for (String severity : List.of("SUCCESS", "WARN", "ERROR")) {
                fixture(kind, severity, true);
                var result = service.observe(9);
                assertEquals(AlertExecutionStatus.valueOf(severity), result.status());
                assertEquals(kind, result.summary().path("resourceKind").asText());
                assertEquals("COMPLETED", result.summary().path("status").asText());
            }
        }
    }

    @Test
    void missingTerminalResultWarnsAndUnknownKindCannotBecomeSql() throws Exception {
        fixture("PIPE", "SUCCESS", false);
        assertEquals(AlertExecutionStatus.WARN, service.observe(9).status());
        assertEquals("NO_TERMINAL_RESULT", service.observe(9).summary().path("status").asText());
        assertThrows(InvalidAlertRequestException.class, () -> service.options("hooks; drop table core.alerts"));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void fixture(String kind, String severity, boolean terminal) throws Exception {
        ResultSet reference = mock(ResultSet.class);
        when(reference.getString("kind")).thenReturn(kind);
        when(reference.getLong("id")).thenReturn(9L);
        ResultSet result = mock(ResultSet.class);
        when(result.getObject("execution_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(result.getString("status")).thenReturn("COMPLETED");
        when(result.getString("severity")).thenReturn(severity);
        when(result.getObject("finished_at", OffsetDateTime.class)).thenReturn(OffsetDateTime.now());
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper mapper = invocation.getArgument(1);
            if (sql.contains("alert_resource_observers"))
                return List.of(mapper.mapRow(reference, 0));

            return terminal ? List.of(mapper.mapRow(result, 0)) : List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), eq(9L));
    }
}
