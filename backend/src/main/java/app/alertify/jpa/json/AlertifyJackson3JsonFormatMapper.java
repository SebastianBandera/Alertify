package app.alertify.jpa.json;

import java.lang.reflect.Type;

import org.hibernate.type.format.AbstractJsonFormatMapper;

import tools.jackson.databind.json.JsonMapper;

/**
 * Bridges Hibernate 7.2 JSON columns with Jackson 3, whose classes live under the {@code tools.jackson} namespace used by Spring Boot 4.
 */
public final class AlertifyJackson3JsonFormatMapper extends AbstractJsonFormatMapper {

    private final JsonMapper jsonMapper;

    public AlertifyJackson3JsonFormatMapper() {
        this(JsonMapper.builder().build());
    }

    AlertifyJackson3JsonFormatMapper(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected <T> T fromString(CharSequence charSequence, Type type) {
        return jsonMapper.readValue(charSequence.toString(), jsonMapper.constructType(type));
    }

    @Override
    protected <T> String toString(T value, Type type) {
        return jsonMapper.writerFor(jsonMapper.constructType(type)).writeValueAsString(value);
    }
}
