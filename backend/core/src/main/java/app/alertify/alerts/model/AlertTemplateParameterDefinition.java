package app.alertify.alerts.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.envers.AuditTable;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.hibernate.type.SqlTypes;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * Persistent metadata for one field annotated with {@code @AlertParameter}.
 */
@Entity
@Audited
@AuditTable(value = "alert_template_parameters_aud", schema = "audit")
@Table(
    name = "alert_template_parameters",
    schema = "core",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_alert_template_parameters_template_key",
        columnNames = { "alert_template_id", "parameter_key" }
    )
)
public class AlertTemplateParameterDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @NotAudited
    @Column(nullable = false)
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alert_template_id", nullable = false, columnDefinition = "text", updatable = false)
    private AlertTemplateDefinition template;

    @Column(name = "parameter_key", nullable = false, columnDefinition = "text", updatable = false)
    private String parameterKey;

    @Column(name = "label_key", nullable = false, columnDefinition = "text")
    private String labelKey;

    @Column(name = "description_key", nullable = false, columnDefinition = "text")
    private String descriptionKey;

    @Column(name = "java_type", nullable = false, columnDefinition = "text")
    private String javaType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_sources", nullable = false, columnDefinition = "jsonb")
    private Set<AlertParameterSource> allowedSources = new LinkedHashSet<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> options = new ArrayList<>();

    @Column(name = "parameter_order", nullable = false)
    private int parameterOrder;

    @Column(nullable = false)
    private boolean required;

    @CreationTimestamp
    @NotAudited
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @NotAudited
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AlertTemplateParameterDefinition() {
    }

    public AlertTemplateParameterDefinition(AlertTemplateDefinition template, String parameterKey, String labelKey, String descriptionKey, String javaType, Set<AlertParameterSource> allowedSources, List<String> options, int parameterOrder, boolean required) {
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.parameterKey = Objects.requireNonNull(parameterKey, "parameterKey must not be null");
        synchronize(labelKey, descriptionKey, javaType, allowedSources, options, parameterOrder, required);
    }

    public Long getId() {
        return id;
    }

    public long getVersion() {
        return version;
    }

    public AlertTemplateDefinition getTemplate() {
        return template;
    }

    public String getParameterKey() {
        return parameterKey;
    }

    public String getLabelKey() {
        return labelKey;
    }

    public String getDescriptionKey() {
        return descriptionKey;
    }

    public String getJavaType() {
        return javaType;
    }

    public Set<AlertParameterSource> getAllowedSources() {
        return Collections.unmodifiableSet(allowedSources);
    }

    public List<String> getOptions() {
        return Collections.unmodifiableList(options);
    }

    public int getParameterOrder() {
        return parameterOrder;
    }

    public boolean isRequired() {
        return required;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void synchronize(String labelKey, String descriptionKey, String javaType, Set<AlertParameterSource> allowedSources, List<String> options, int parameterOrder, boolean required) {
        this.labelKey = Objects.requireNonNull(labelKey, "labelKey must not be null");
        this.descriptionKey = Objects.requireNonNull(descriptionKey, "descriptionKey must not be null");
        this.javaType = Objects.requireNonNull(javaType, "javaType must not be null");
        replaceAllowedSources(allowedSources);
        replaceOptions(options);
        if (parameterOrder < 0)
            throw new IllegalArgumentException("parameterOrder must not be negative");
        this.parameterOrder = parameterOrder;
        this.required = required;
    }

    private void replaceAllowedSources(Set<AlertParameterSource> allowedSources) {
        Objects.requireNonNull(allowedSources, "allowedSources must not be null");
        if (allowedSources.isEmpty())
            throw new IllegalArgumentException("allowedSources must not be empty");
        this.allowedSources.clear();
        this.allowedSources.addAll(allowedSources);
    }

    private void replaceOptions(List<String> options) {
        this.options.clear();
        if (options != null)
            this.options.addAll(options);
    }
}
