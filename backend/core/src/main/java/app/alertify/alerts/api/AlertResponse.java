package app.alertify.alerts.api;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import app.alertify.configuration.api.TagResponse;

public record AlertResponse(
    Long id,
    long version,
    Long templateId,
    String templateKey,
    String templateNameKey,
    String name,
    String description,
    String cronExpression,
    boolean enabled,
    boolean allowConcurrentExecutions,
    Set<TagResponse> tags,
    List<AlertParameterValueResponse> parameters,
    Instant createdAt,
    Instant updatedAt,
    /** Since when past WARN/ERROR results persist until seen; null while the option is off. */
    Instant persistentIssuesSince
) {
    public AlertResponse(
        Long id, long version, Long templateId, String templateKey, String templateNameKey,
        String name, String description, String cronExpression, boolean enabled, boolean allowConcurrentExecutions,
        Set<TagResponse> tags, List<AlertParameterValueResponse> parameters,
        Instant createdAt, Instant updatedAt
    ) {
        this(
            id, version, templateId, templateKey, templateNameKey, name, description,
            cronExpression, enabled, allowConcurrentExecutions, tags, parameters, createdAt, updatedAt, null
        );
    }

    public AlertResponse(
        Long id, long version, Long templateId, String templateKey, String templateNameKey,
        String name, String description, String cronExpression, boolean enabled,
        Set<TagResponse> tags, List<AlertParameterValueResponse> parameters,
        Instant createdAt, Instant updatedAt
    ) {
        this(
            id, version, templateId, templateKey, templateNameKey, name, description,
            cronExpression, enabled, false, tags, parameters, createdAt, updatedAt
        );
    }
}
