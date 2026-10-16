package app.alertify.ai.template;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.ai.api.AiPage;
import app.alertify.ai.api.AiProcedureParameterValue;
import app.alertify.ai.template.AiTemplateBindingCandidates.Candidate;
import app.alertify.ai.template.AiTemplateParameterValidation.Issue;
import app.alertify.alerts.template.TemplateParameterRules;
import app.alertify.alerts.template.TemplateParameterValidation;
import app.alertify.alerts.template.TemplateParameterValidation.Violation;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.PipeRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.jpa.repository.ProcedureTemplateDefinitionRepository;
import app.alertify.jpa.repository.ProcedureTemplateParameterDefinitionRepository;

@Service
@Transactional(readOnly = true)
public class AiTemplateParametersService {
    private final AlertTemplateDefinitionRepository alertTemplates;
    private final ProcedureTemplateDefinitionRepository procedureTemplates;
    private final AlertTemplateParameterDefinitionRepository alertParameters;
    private final ProcedureTemplateParameterDefinitionRepository procedureParameters;
    private final ApplicationConfigurationRepository configurations;
    private final ApplicationSecretRepository secrets;
    private final ProcedureRepository procedures;
    private final PipeRepository pipes;

    public AiTemplateParametersService(AlertTemplateDefinitionRepository alertTemplates, ProcedureTemplateDefinitionRepository procedureTemplates, AlertTemplateParameterDefinitionRepository alertParameters, ProcedureTemplateParameterDefinitionRepository procedureParameters, ApplicationConfigurationRepository configurations, ApplicationSecretRepository secrets, ProcedureRepository procedures, PipeRepository pipes) {
        this.alertTemplates = alertTemplates;
        this.procedureTemplates = procedureTemplates;
        this.alertParameters = alertParameters;
        this.procedureParameters = procedureParameters;
        this.configurations = configurations;
        this.secrets = secrets;
        this.procedures = procedures;
        this.pipes = pipes;
    }

    public AiTemplateParameterValidation validate(String templateKey, List<AiProcedureParameterValue> parameters) {
        var selected = AiTemplateLookup.select(templateKey, alertTemplates, procedureTemplates);
        if (parameters == null || parameters.size() > 100)
            throw new IllegalArgumentException("Provide a parameter list with at most 100 entries");

        List<TemplateParameterRules> definitions = rules(selected);
        Map<String, TemplateParameterRules> byKey = new LinkedHashMap<>();
        definitions.forEach(definition -> byKey.put(definition.key(), definition));
        Map<String, AiProcedureParameterValue> requested = new LinkedHashMap<>();
        List<Issue> issues = new ArrayList<>();
        List<String> defaults = new ArrayList<>();
        List<String> omitted = new ArrayList<>();
        for (AiProcedureParameterValue parameter : parameters) {
            if (parameter == null || parameter.parameterKey() == null || parameter.parameterKey().isBlank() || parameter.parameterKey().length() > 255) {
                issues.add(new Issue(null, "PARAMETER_KEY_REQUIRED", "Every entry needs an exact parameterKey"));
                continue;
            }
            if (!byKey.containsKey(parameter.parameterKey())) {
                issues.add(new Issue(parameter.parameterKey(), "UNKNOWN_PARAMETER", "Unknown parameter for the selected template"));
                continue;
            }
            if (requested.putIfAbsent(parameter.parameterKey(), parameter) != null)
                issues.add(new Issue(parameter.parameterKey(), "DUPLICATE_PARAMETER", "Parameter was provided more than once"));

        }
        for (TemplateParameterRules definition : definitions) {
            AiProcedureParameterValue parameter = requested.get(definition.key());
            if (parameter == null && definition.defaultValue() != null) {
                parameter = new AiProcedureParameterValue(definition.key(), app.alertify.ai.api.AiProcedureParameterSource.TEXT, definition.defaultValue(), null, null, null, null);
                defaults.add(definition.key());
            }
            if (parameter == null) {
                if (definition.required())
                    issues.add(new Issue(definition.key(), "REQUIRED_PARAMETER", "Required parameter has no value or default"));
                else
                    omitted.add(definition.key());

                continue;
            }
            try {
                if (parameter.textValue() != null && parameter.textValue().length() > 1_048_576)
                    throw TemplateParameterValidation.violation("TEXT_TOO_LARGE", "Text value exceeds the parameter input size limit");

                var value = parameter.toRequest();
                TemplateParameterValidation.source(definition, value.source());
                switch (value.source()) {
                    case TEXT -> TemplateParameterValidation.text(definition, value.textValue());
                    case CONFIGURATION -> {
                        var metadata = configurations.findProjectedById(value.configurationId()).orElseThrow(() -> missing("configuration"));
                        TemplateParameterValidation.configuration(definition, metadata.getValueType(), metadata.isWritable());
                    }
                    case SECRET -> {
                        var metadata = secrets.findProjectedById(value.secretId()).orElseThrow(() -> missing("secret"));
                        TemplateParameterValidation.secret(definition, metadata.getValueType(), metadata.isWritable());
                    }
                    case PROCEDURE -> procedures.findProjectedById(value.procedureId()).orElseThrow(() -> missing("procedure"));
                    case PIPE -> pipes.findProjectedById(value.pipeId()).orElseThrow(() -> missing("pipe"));
                    case PIPE_OUTPUT -> throw TemplateParameterValidation.violation("EXECUTION_CONTEXT_ONLY", "PIPE_OUTPUT requires a pipe execution context");
                }
            } catch (Violation exception) {
                issues.add(new Issue(definition.key(), exception.code(), exception.getMessage()));
            } catch (IllegalArgumentException exception) {
                issues.add(new Issue(definition.key(), "INVALID_ASSIGNMENT", "Provide a valid source and its text value or positive existing reference ID"));
            }
        }
        return new AiTemplateParameterValidation(selected.type(), selected.key(), issues.isEmpty(), List.copyOf(issues), List.copyOf(defaults), List.copyOf(omitted),
                "Persisted parameter definitions and reference metadata only. Bound values, template constructors, cross-parameter behavior, connectivity, resource enabled state and runtime execution are not verified. Revalidate on create/update.");
    }

    public AiTemplateBindingCandidates candidates(String templateKey, String parameterKey, Integer page, Integer size) {
        var selected = AiTemplateLookup.select(templateKey, alertTemplates, procedureTemplates);
        int number = page == null ? 0 : page;
        int limit = size == null ? 20 : size;
        if (number < 0 || limit < 1 || limit > 100)
            throw new IllegalArgumentException("Page must be zero or greater and size must be between 1 and 100");

        TemplateParameterRules definition = rules(selected).stream().filter(rule -> rule.key().equals(parameterKey)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Copy an exact parameterKey from the selected template"));
        List<Candidate> result = new ArrayList<>();
        if (permits(definition, AlertParameterSource.CONFIGURATION)) {
            for (var metadata : configurations.findAllProjectedBy()) {
                try {
                    TemplateParameterValidation.configuration(definition, metadata.getValueType(), metadata.isWritable());
                    result.add(new Candidate(AlertParameterSource.CONFIGURATION, metadata.getId(), metadata.getName(), metadata.getDescription(), metadata.getValueType().name(), metadata.isWritable(), null));
                } catch (Violation ignored) { }
            }
        }
        if (permits(definition, AlertParameterSource.SECRET)) {
            for (var metadata : secrets.findAllProjectedBy()) {
                try {
                    TemplateParameterValidation.secret(definition, metadata.getValueType(), metadata.isWritable());
                    result.add(new Candidate(AlertParameterSource.SECRET, metadata.getId(), metadata.getName(), metadata.getDescription(), metadata.getValueType().name(), metadata.isWritable(), null));
                } catch (Violation ignored) { }
            }
        }
        if (permits(definition, AlertParameterSource.PROCEDURE)) {
            for (var metadata : procedures.findAllProjectedBy())
                result.add(new Candidate(AlertParameterSource.PROCEDURE, metadata.getId(), metadata.getName(), metadata.getDescription(), null, null, metadata.isEnabled()));

        }
        if (permits(definition, AlertParameterSource.PIPE)) {
            for (var metadata : pipes.findAllProjectedBy())
                result.add(new Candidate(AlertParameterSource.PIPE, metadata.getId(), metadata.getName(), metadata.getDescription(), null, null, metadata.isEnabled()));

        }
        result.sort(Comparator.comparing(Candidate::name, String.CASE_INSENSITIVE_ORDER).thenComparing(value -> value.source().name()).thenComparing(Candidate::id));
        int from = (int) Math.min((long) number * limit, result.size());
        int to = (int) Math.min((long) from + limit, result.size());
        return new AiTemplateBindingCandidates(selected.type(), selected.key(), definition.key(), definition.bindingAllowed(), definition.allowedSources(),
                AiPage.from(new PageImpl<>(List.copyOf(result.subList(from, to)), PageRequest.of(number, limit), result.size())));
    }

    private List<TemplateParameterRules> rules(AiTemplateLookup.Selection selected) {
        return "ALERT".equals(selected.type())
                ? alertParameters.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(selected.id()).stream().map(TemplateParameterRules::of).toList()
                : procedureParameters.findAllByTemplate_IdOrderByParameterOrderAscIdAsc(selected.id()).stream().map(TemplateParameterRules::of).toList();
    }

    private static boolean permits(TemplateParameterRules rules, AlertParameterSource source) {
        try {
            TemplateParameterValidation.source(rules, source);
            return true;
        } catch (Violation exception) {
            return false;
        }
    }

    private static Violation missing(String resource) {
        return TemplateParameterValidation.violation("REFERENCE_NOT_FOUND", "Referenced " + resource + " was not found");
    }
}
