package app.alertify.alerts.model;

import java.time.Instant;
import java.util.Objects;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.envers.AuditTable;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ApplicationSecret;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Configured value for one alert parameter. Exactly one source-specific field
 * is populated: text, configuration reference, or secret reference.
 */
@Entity
@Audited
@AuditTable(value = "alert_parameter_values_aud", schema = "audit")
@Table(
    name = "alert_parameter_values",
    schema = "core",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_alert_parameter_values_alert_parameter",
        columnNames = { "alert_id", "template_parameter_id" }
    )
)
public class AlertParameterValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @NotAudited
    @Column(nullable = false)
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alert_id", nullable = false, updatable = false)
    private Alert alert;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_parameter_id", nullable = false, updatable = false)
    private AlertTemplateParameterDefinition templateParameter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AlertParameterSource source;

    @Column(name = "text_value", columnDefinition = "text")
    private String textValue;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "configuration_id")
    private ApplicationConfiguration configuration;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "secret_id")
    private ApplicationSecret secret;

    @CreationTimestamp
    @NotAudited
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @NotAudited
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AlertParameterValue() {
    }

    private AlertParameterValue(Alert alert, AlertTemplateParameterDefinition templateParameter) {
        this.alert = Objects.requireNonNull(alert, "alert must not be null");
        this.templateParameter = Objects.requireNonNull(templateParameter, "templateParameter must not be null");
        if (!alert.getTemplate().getId().equals(templateParameter.getTemplate().getId()))
            throw new IllegalArgumentException("templateParameter does not belong to the alert template");
    }

    public static AlertParameterValue text(Alert alert, AlertTemplateParameterDefinition templateParameter, String value) {
        AlertParameterValue parameterValue = new AlertParameterValue(alert, templateParameter);
        parameterValue.replaceWithText(value);
        return parameterValue;
    }

    public static AlertParameterValue configuration(Alert alert, AlertTemplateParameterDefinition templateParameter, ApplicationConfiguration configuration) {
        AlertParameterValue parameterValue = new AlertParameterValue(alert, templateParameter);
        parameterValue.replaceWithConfiguration(configuration);
        return parameterValue;
    }

    public static AlertParameterValue secret(Alert alert, AlertTemplateParameterDefinition templateParameter, ApplicationSecret secret) {
        AlertParameterValue parameterValue = new AlertParameterValue(alert, templateParameter);
        parameterValue.replaceWithSecret(secret);
        return parameterValue;
    }

    public Long getId() {
        return id;
    }

    public long getVersion() {
        return version;
    }

    public Alert getAlert() {
        return alert;
    }

    public AlertTemplateParameterDefinition getTemplateParameter() {
        return templateParameter;
    }

    public AlertParameterSource getSource() {
        return source;
    }

    public String getTextValue() {
        return textValue;
    }

    public ApplicationConfiguration getConfiguration() {
        return configuration;
    }

    public ApplicationSecret getSecret() {
        return secret;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void replaceWithText(String value) {
        requireAllowed(AlertParameterSource.TEXT);
        source = AlertParameterSource.TEXT;
        textValue = Objects.requireNonNull(value, "value must not be null");
        configuration = null;
        secret = null;
    }

    public void replaceWithConfiguration(ApplicationConfiguration configuration) {
        requireAllowed(AlertParameterSource.CONFIGURATION);
        source = AlertParameterSource.CONFIGURATION;
        textValue = null;
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        secret = null;
    }

    public void replaceWithSecret(ApplicationSecret secret) {
        requireAllowed(AlertParameterSource.SECRET);
        source = AlertParameterSource.SECRET;
        textValue = null;
        configuration = null;
        this.secret = Objects.requireNonNull(secret, "secret must not be null");
    }

    private void requireAllowed(AlertParameterSource source) {
        if (!templateParameter.getAllowedSources().contains(source))
            throw new IllegalArgumentException(source + " is not allowed for parameter " + templateParameter.getParameterKey());
    }
}
