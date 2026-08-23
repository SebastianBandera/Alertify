package app.alertify.jpa.entity;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import tools.jackson.databind.JsonNode;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.envers.AuditJoinTable;
import org.hibernate.envers.AuditTable;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * Persistent application configuration containing a typed JSON value and
 * configuration-scoped tags. Envers audits changes to its business fields
 * and tag associations, excluding technical version and timestamp fields.
 */
@Entity
@Audited
@AuditTable(value = "configurations_aud", schema = "audit")
@Table(
    name = "configurations",
    schema = "core",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_configurations_name",
        columnNames = "name"
    )
)
public class ApplicationConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @NotAudited
    @Column(nullable = false)
    private long version;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 32)
    private ConfigurationValueType valueType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "configuration_value", nullable = false, columnDefinition = "jsonb")
    private JsonNode value;

    @CreationTimestamp
    @NotAudited
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @NotAudited
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "configuration_tag",
        schema = "core",
        joinColumns = @JoinColumn(name = "configuration_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    @AuditJoinTable(name = "configuration_tag_aud", schema = "audit")
    private Set<Tag> tags = new LinkedHashSet<>();

    protected ApplicationConfiguration() {
    }

    public ApplicationConfiguration(
            String name,
            String description,
            ConfigurationValueType valueType,
            JsonNode value,
            Set<Tag> tags) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.description = description;
        this.valueType = Objects.requireNonNull(valueType, "valueType must not be null");
        this.value = Objects.requireNonNull(value, "value must not be null");
        replaceTags(tags);
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

    public String getDescription() {
        return description;
    }

    public ConfigurationValueType getValueType() {
        return valueType;
    }

    public JsonNode getValue() {
        return value;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Set<Tag> getTags() {
        return Collections.unmodifiableSet(tags);
    }

    public void rename(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    public void changeDescription(String description) {
        this.description = description;
    }

    public void changeValue(ConfigurationValueType valueType, JsonNode value) {
        this.valueType = Objects.requireNonNull(valueType, "valueType must not be null");
        this.value = Objects.requireNonNull(value, "value must not be null");
    }

    public void replaceTags(Set<Tag> tags) {
        this.tags.clear();
        if (tags != null)
            this.tags.addAll(tags);
    }
}
