package app.alertify.alerts.model;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Explicit projection and update mapping for the potentially large runtime
 * state stored in {@code core.alerts}. Normal {@link Alert} queries do not map
 * or select this column.
 */
@Entity
@Table(name = "alerts", schema = "core")
public class AlertState {

    @Id
    @Column(name = "id", updatable = false)
    private Long alertId;

    @Column(nullable = false, columnDefinition = "text")
    private String state;

    protected AlertState() {
    }

    AlertState(Long alertId, String state) {
        this.alertId = Objects.requireNonNull(alertId, "alertId must not be null");
        replaceState(state);
    }

    public Long getAlertId() {
        return alertId;
    }

    public String getState() {
        return state;
    }

    public void replaceState(String state) {
        this.state = state == null ? "" : state;
    }
}
