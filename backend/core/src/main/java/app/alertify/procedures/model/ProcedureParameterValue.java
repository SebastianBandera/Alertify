package app.alertify.procedures.model;

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
 * Configured value for one procedure parameter. Exactly one source-specific
 * field is populated: text, configuration reference, secret reference, or a
 * reference to another procedure.
 */
@Entity
@Audited
@AuditTable(value = "procedure_parameter_values_aud", schema = "audit")
@Table(name = "procedure_parameter_values", schema = "core", uniqueConstraints =
        @UniqueConstraint(name = "uq_procedure_parameter_values_owner_parameter",
                columnNames = { "owner_procedure_id", "template_parameter_id" }))
public class ProcedureParameterValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @NotAudited
    @Column(nullable = false)
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_procedure_id", nullable = false, updatable = false)
    private Procedure owner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_parameter_id", nullable = false, updatable = false)
    private ProcedureTemplateParameterDefinition templateParameter;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referenced_procedure_id")
    private Procedure referencedProcedure;

    @CreationTimestamp
    @NotAudited
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @NotAudited
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProcedureParameterValue() {
    }

    private ProcedureParameterValue(Procedure owner, ProcedureTemplateParameterDefinition parameter) {
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.templateParameter = Objects.requireNonNull(parameter, "parameter must not be null");
        if (!owner.getTemplate().getTemplateKey().equals(parameter.getTemplate().getTemplateKey()))
            throw new IllegalArgumentException("parameter does not belong to the procedure template");
    }

    public static ProcedureParameterValue text(Procedure owner, ProcedureTemplateParameterDefinition parameter, String value) {
        ProcedureParameterValue result = new ProcedureParameterValue(owner, parameter);
        result.replaceWithText(value);
        return result;
    }

    public static ProcedureParameterValue configuration(Procedure owner, ProcedureTemplateParameterDefinition parameter, ApplicationConfiguration value) {
        ProcedureParameterValue result = new ProcedureParameterValue(owner, parameter);
        result.replaceWithConfiguration(value);
        return result;
    }

    public static ProcedureParameterValue secret(Procedure owner, ProcedureTemplateParameterDefinition parameter, ApplicationSecret value) {
        ProcedureParameterValue result = new ProcedureParameterValue(owner, parameter);
        result.replaceWithSecret(value);
        return result;
    }

    public static ProcedureParameterValue procedure(Procedure owner, ProcedureTemplateParameterDefinition parameter, Procedure value) {
        ProcedureParameterValue result = new ProcedureParameterValue(owner, parameter);
        result.replaceWithProcedure(value);
        return result;
    }

    public void replaceWithText(String value) {
        requireSource(AlertParameterSource.TEXT);
        if (!templateParameter.isBindingAllowed() && !templateParameter.getOptions().contains(value))
            throw new IllegalArgumentException("value must be one of the declared options");

        set(AlertParameterSource.TEXT, Objects.requireNonNull(value, "value must not be null"), null, null, null);
    }

    public void replaceWithConfiguration(ApplicationConfiguration value) {
        requireBindingAndSource(AlertParameterSource.CONFIGURATION);
        set(AlertParameterSource.CONFIGURATION, null, Objects.requireNonNull(value), null, null);
    }

    public void replaceWithSecret(ApplicationSecret value) {
        requireBindingAndSource(AlertParameterSource.SECRET);
        set(AlertParameterSource.SECRET, null, null, Objects.requireNonNull(value), null);
    }

    public void replaceWithProcedure(Procedure value) {
        requireBindingAndSource(AlertParameterSource.PROCEDURE);
        set(AlertParameterSource.PROCEDURE, null, null, null, Objects.requireNonNull(value));
    }

    private void set(AlertParameterSource source, String text, ApplicationConfiguration configuration, ApplicationSecret secret, Procedure procedure) {
        this.source = source;
        this.textValue = text;
        this.configuration = configuration;
        this.secret = secret;
        this.referencedProcedure = procedure;
    }

    private void requireBindingAndSource(AlertParameterSource value) {
        if (!templateParameter.isBindingAllowed())
            throw new IllegalArgumentException("binding is not allowed for parameter " + templateParameter.getParameterKey());

        requireSource(value);
    }

    private void requireSource(AlertParameterSource value) {
        if (!templateParameter.getAllowedSources().contains(value))
            throw new IllegalArgumentException("source " + value + " is not allowed for parameter " + templateParameter.getParameterKey());
    }

    public Long getId() { return id; }
    public long getVersion() { return version; }
    public Procedure getOwner() { return owner; }
    public ProcedureTemplateParameterDefinition getTemplateParameter() { return templateParameter; }
    public AlertParameterSource getSource() { return source; }
    public String getTextValue() { return textValue; }
    public ApplicationConfiguration getConfiguration() { return configuration; }
    public ApplicationSecret getSecret() { return secret; }
    public Procedure getReferencedProcedure() { return referencedProcedure; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
