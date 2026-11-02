package app.alertify.alerts.execution;

import java.math.BigInteger;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.SmartExecutionIntervalSource;
import app.alertify.dashboard.DashboardEventPublisher;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;

/** Resolves live intervals and atomically records one failed execution per incident. */
@Service
public class SmartExecutionIntervalResolver {

    private final ApplicationConfigurationRepository configurations;
    private final JdbcTemplate jdbc;
    private final AlertExecutionPersistenceService persistence;
    private final DashboardEventPublisher dashboard;

    public SmartExecutionIntervalResolver(ApplicationConfigurationRepository configurations, JdbcTemplate jdbc, AlertExecutionPersistenceService persistence, DashboardEventPublisher dashboard) {
        this.configurations = configurations;
        this.jdbc = jdbc;
        this.persistence = persistence;
        this.dashboard = dashboard;
    }

    @Transactional
    public Integer resolve(Alert alert) {
        if (alert.getSmartExecutionIntervalSource() != SmartExecutionIntervalSource.CONFIGURATION) {
            jdbc.update("delete from core.smart_execution_interval_incidents where alert_id = ?", alert.getId());
            return alert.getSmartExecutionIntervalHours();
        }

        jdbc.update("insert into core.smart_execution_interval_incidents(alert_id) values (?) on conflict do nothing", alert.getId());
        String previous = jdbc.queryForObject("select problem from core.smart_execution_interval_incidents where alert_id = ? for update", String.class, alert.getId());
        Long id = alert.getSmartExecutionIntervalConfigurationId();
        var configuration = id == null ? null : configurations.findById(id).orElse(null);
        String reason = configuration == null ? "MISSING" : configuration.getValueType() != ConfigurationValueType.INTEGER ? "TYPE" : null;
        if (reason == null && (configuration.getValue() == null || !configuration.getValue().isIntegralNumber()))
            reason = "VALUE";

        if (reason != null) {
            String problem = id + ":" + reason;
            if (!Objects.equals(previous, problem)) {
                String message = "Smart execution interval configuration " + (id == null ? "is not selected or was deleted" : id + switch (reason) {
                    case "MISSING" -> " was not found";
                    case "TYPE" -> " must have type INTEGER";
                    default -> " does not contain an integer";
                });
                persistence.persistSmartIntervalFailure(alert.getId(), message);
                dashboard.alertChangedAfterCommit(alert.getId());
                jdbc.update("update core.smart_execution_interval_incidents set problem = ? where alert_id = ?", problem, alert.getId());
            }
            return null;
        }

        if (previous != null)
            jdbc.update("update core.smart_execution_interval_incidents set problem = null where alert_id = ?", alert.getId());

        return configuration.getValue().bigIntegerValue().max(BigInteger.ONE).min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
    }
}
