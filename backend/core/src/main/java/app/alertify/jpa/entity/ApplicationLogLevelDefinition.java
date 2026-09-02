package app.alertify.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Normalized catalog entry for the severity assigned to an application log
 * event.
 */
@Entity
@Table(name = "log_levels", schema = "audit")
public class ApplicationLogLevelDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Short id;

    @Column(nullable = false, length = 32, unique = true, updatable = false)
    private String code;

    protected ApplicationLogLevelDefinition() {
    }

    public Short getId() {
        return id;
    }

    public String getCode() {
        return code;
    }
}
