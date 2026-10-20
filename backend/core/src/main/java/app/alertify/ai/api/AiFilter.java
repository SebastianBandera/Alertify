package app.alertify.ai.api;

/** One allow-listed dynamic filter; value uses Alertify's existing filter syntax. */
public record AiFilter(String field, String value) {
    public AiFilter {
        if (field == null || field.isBlank())
            throw new IllegalArgumentException("Filter field must not be blank");

        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Filter value must not be blank");
    }
}
