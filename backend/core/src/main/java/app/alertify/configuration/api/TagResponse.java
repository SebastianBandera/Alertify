package app.alertify.configuration.api;

import java.time.Instant;

import app.alertify.jpa.entity.TagScope;

public record TagResponse(
    Long id,
    long version,
    TagScope scope,
    String name,
    String color,
    Instant createdAt,
    Instant updatedAt
) {
}
