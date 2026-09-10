package app.alertify.jpa.entity;

import java.time.Instant;
import java.util.Objects;

import tools.jackson.databind.JsonNode;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.envers.AuditTable;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * Backend-owned setting (e.g. the symmetric-key part used to protect secrets,
 * or the cron quiet-hours window), kept separate from user-editable
 * {@link ApplicationConfiguration} rows. {@link #isValueHidden()} decides
 * whether the API ever returns the value at all; it is fixed per row when the
 * row is created (by a migration, or by importing an export archive) and is
 * never changed afterwards. The value itself is always excluded from Envers
 * auditing regardless of this flag, since it can be sensitive.
 */
@Entity
@Audited
@AuditTable(value = "system_configurations_aud", schema = "audit")
@Table(
    name = "system_configurations",
    schema = "core",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_system_configurations_name",
        columnNames = "name"
    )
)
public class SystemConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @NotAudited
    @Column(nullable = false)
    private long version;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @NotAudited
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode value;

    @Column(name = "value_hidden", nullable = false)
    private boolean valueHidden;

    @CreationTimestamp
    @NotAudited
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @NotAudited
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SystemConfiguration() {
    }

    public SystemConfiguration(String name, JsonNode value, boolean valueHidden) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.value = Objects.requireNonNull(value, "value must not be null");
        this.valueHidden = valueHidden;
    }

    public Long getId() {
        return id;
    }

    public long getVersion() {
        return version;
    }

    public String getName() {
        return name;
    }

    public JsonNode getValue() {
        return value;
    }

    public boolean isValueHidden() {
        return valueHidden;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void changeValue(JsonNode value) {
        this.value = Objects.requireNonNull(value, "value must not be null");
    }
}
