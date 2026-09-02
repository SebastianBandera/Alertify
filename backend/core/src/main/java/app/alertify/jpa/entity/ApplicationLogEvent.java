package app.alertify.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Normalized catalog entry identifying the kind of application event stored
 * in the log.
 */
@Entity
@Table(name = "log_events", schema = "audit")
public class ApplicationLogEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Short id;

    @Column(nullable = false, length = 128, unique = true, updatable = false)
    private String code;

    protected ApplicationLogEvent() {
    }

    public Short getId() {
        return id;
    }

    public String getCode() {
        return code;
    }
}
