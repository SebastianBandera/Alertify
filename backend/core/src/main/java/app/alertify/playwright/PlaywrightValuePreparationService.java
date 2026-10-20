package app.alertify.playwright;

import org.springframework.stereotype.Service;
import app.alertify.alerts.AlertExecutionValue;
import app.alertify.alerts.AlertExecutionValueSource;
import app.alertify.configuration.service.ConfigurationExpressionService;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.services.secret.SecretAccessContext;
import app.alertify.services.secret.SecretAccessService;
import tools.jackson.databind.json.JsonMapper;

/** Resolves declared dependencies only; procedures remain lazy capabilities. */
@Service
public class PlaywrightValuePreparationService {
    private final ApplicationSecretRepository secrets;
    private final ProcedureRepository procedures;
    private final SecretAccessService access;
    private final ConfigurationExpressionService configurations;
    private final JsonMapper json;

    public PlaywrightValuePreparationService(ApplicationSecretRepository secrets, ProcedureRepository procedures, SecretAccessService access, ConfigurationExpressionService configurations, JsonMapper json) {
        this.secrets = secrets;
        this.procedures = procedures;
        this.access = access;
        this.configurations = configurations;
        this.json = json;
    }

    public AlertExecutionValue resolve(PlaywrightPageEngine.ValueReference reference, SecretAccessContext context) {
        String value = switch (reference.source()) {
            case CONFIGURATION -> configurations.getResolvedValueByName(reference.name());
            case SECRET -> {
                var secret = secrets.findByNameIgnoreCase(reference.name()).orElseThrow(() -> new IllegalArgumentException("Referenced secret does not exist"));
                if (reference.pointer() == null) {
                    if (secret.getValueType() != SecretValueType.STRING && secret.getValueType() != SecretValueType.EXPRESSION)
                        throw new IllegalArgumentException("Use FILL SECRET with /username or /password for a credential secret");

                    yield access.getValueByName(reference.name(), context);
                }
                if (secret.getValueType() != SecretValueType.USERNAME_PASSWORD)
                    throw new IllegalArgumentException("FILL SECRET POINTER requires a USERNAME_PASSWORD secret");

                var field = json.readTree(access.getValueByName(reference.name(), context)).at(reference.pointer());
                if (!field.isString() || field.stringValue().isEmpty())
                    throw new IllegalArgumentException("The requested credential field is not configured");

                yield field.stringValue();
            }
            case PROCEDURE -> {
                var procedure = procedure(reference.name());
                yield json.writeValueAsString(java.util.Map.of("procedureId", procedure.getId(), "pointer", reference.pointer()));
            }
        };
        return new AlertExecutionValue(reference.source(), reference.key(), value);
    }

    public app.alertify.procedures.model.Procedure procedure(String name) {
        var procedure = procedures.findByNameIgnoreCase(name).orElseThrow(() -> new IllegalArgumentException("Referenced procedure does not exist"));
        if (!procedure.isEnabled())
            throw new IllegalArgumentException("Referenced procedure is disabled");

        return procedure;
    }

    public Object secretReferences() {
        return secrets.findAllProjectedBy().stream().map(value -> java.util.Map.of("name", value.getName(), "type", value.getValueType())).toList();
    }
}
