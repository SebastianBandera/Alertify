package app.alertify.procedures.service;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

import app.alertify.configuration.api.TagResponse;
import app.alertify.procedures.api.ProcedureExecutionResponse;
import app.alertify.procedures.api.ProcedureParameterValueResponse;
import app.alertify.procedures.api.ProcedureResponse;
import app.alertify.procedures.api.ProcedureTemplateParameterResponse;
import app.alertify.procedures.api.ProcedureTemplateResponse;
import app.alertify.procedures.api.ProcedureTemplateTagResponse;
import app.alertify.procedures.model.Procedure;
import app.alertify.procedures.model.ProcedureExecution;
import app.alertify.procedures.model.ProcedureParameterValue;
import app.alertify.procedures.model.ProcedureTemplateDefinition;
import app.alertify.procedures.model.ProcedureTemplateParameterDefinition;

/** Converts procedure entities into the API responses served by the controllers. */
final class ProcedureMapper {

    private ProcedureMapper() {
    }

    static ProcedureTemplateResponse toTemplate(ProcedureTemplateDefinition template, List<ProcedureTemplateParameterDefinition> parameters, long count) {
        return new ProcedureTemplateResponse(
                template.getId(), template.getVersion(), template.getTemplateKey(), template.getNameKey(),
                template.getDescriptionKey(), template.getRequiredCapability(), template.isSensitiveResult(),
                template.getTags().stream().map(tag -> new ProcedureTemplateTagResponse(tag.nameKey(), tag.color())).toList(),
                count, parameters.stream().map(ProcedureMapper::toTemplateParameter).toList(),
                template.getCreatedAt(), template.getUpdatedAt()
        );
    }

    static ProcedureResponse toProcedure(Procedure procedure, List<ProcedureParameterValue> values) {
        ProcedureTemplateDefinition template = procedure.getTemplate();
        return new ProcedureResponse(
                procedure.getId(), procedure.getVersion(), template.getId(), template.getTemplateKey(),
                template.getNameKey(), procedure.getName(), procedure.getDescription(), procedure.getCronExpression(), procedure.isEnabled(),
                procedure.isConcurrentExecutionAllowed(),
                procedure.getTags().stream()
                        .sorted(Comparator.comparing(tag -> tag.getName().toLowerCase(java.util.Locale.ROOT)))
                        .map(tag -> new TagResponse(tag.getId(), tag.getVersion(), tag.getScope(), tag.getName(),
                                tag.getColor(), tag.getCreatedAt(), tag.getUpdatedAt()))
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                values.stream().map(ProcedureMapper::toParameterValue).toList(),
                procedure.getCreatedAt(), procedure.getUpdatedAt()
        );
    }

    static ProcedureExecutionResponse toExecution(ProcedureExecution execution) {
        Long duration = execution.getFinishedAt() == null ? null
                : Duration.between(execution.getStartedAt(), execution.getFinishedAt()).toMillis();
        Long idle = execution.getWorkStartedAt() == null ? null
                : Duration.between(execution.getStartedAt(), execution.getWorkStartedAt()).toMillis();
        Long work = execution.getFinishedAt() == null || execution.getWorkStartedAt() == null ? null
                : Duration.between(execution.getWorkStartedAt(), execution.getFinishedAt()).toMillis();
        return new ProcedureExecutionResponse(
                execution.getId(), execution.getExecutionId(), execution.getProcedure().getId(),
                execution.getProcedure().getName(), execution.getProcedureVersion(), execution.getStatus(),
                execution.getTrigger(), execution.getRootExecutionId(), execution.getParentAlertExecutionId(),
                execution.getParentProcedureExecutionId(), execution.getDepth(), execution.getStartedAt(),
                execution.getWorkStartedAt(), execution.getFinishedAt(), duration, idle, work,
                execution.getResultJson(), execution.isResultRedacted(), execution.getErrorType(),
                execution.getErrorMessage(), execution.getWorkerName(), execution.getWorkerIpAddress(),
                execution.getWorkerPort(), execution.getWorkerInstanceId(), execution.getTriggeredBy()
        );
    }

    private static ProcedureTemplateParameterResponse toTemplateParameter(ProcedureTemplateParameterDefinition value) {
        return new ProcedureTemplateParameterResponse(
                value.getId(), value.getVersion(), value.getParameterKey(), value.getLabelKey(),
                value.getDescriptionKey(), value.getJavaType(), value.getOptions(), value.isBindingAllowed(),
                value.getDefaultValue(), value.isMultiline(), value.getParameterOrder(), value.isRequired(),
                value.getAllowedSources(), value.getCreatedAt(), value.getUpdatedAt()
        );
    }

    private static ProcedureParameterValueResponse toParameterValue(ProcedureParameterValue value) {
        return new ProcedureParameterValueResponse(
                value.getId(), value.getVersion(), value.getTemplateParameter().getParameterKey(), value.getSource(),
                value.getTextValue(), value.getConfiguration() == null ? null : value.getConfiguration().getId(),
                value.getConfiguration() == null ? null : value.getConfiguration().getName(),
                value.getSecret() == null ? null : value.getSecret().getId(),
                value.getSecret() == null ? null : value.getSecret().getName(),
                value.getReferencedProcedure() == null ? null : value.getReferencedProcedure().getId(),
                value.getReferencedProcedure() == null ? null : value.getReferencedProcedure().getName(),
                value.getCreatedAt(), value.getUpdatedAt()
        );
    }
}
