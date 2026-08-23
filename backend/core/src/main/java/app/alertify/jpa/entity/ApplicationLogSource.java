package app.alertify.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "log_sources", schema = "audit")
public class ApplicationLogSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Short id;

    @Column(nullable = false, length = 128, unique = true, updatable = false)
    private String code;

    protected ApplicationLogSource() {
    }

    public Short getId() {
        return id;
    }

    public String getCode() {
        return code;
    }
}
