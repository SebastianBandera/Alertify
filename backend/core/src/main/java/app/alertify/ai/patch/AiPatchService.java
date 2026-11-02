package app.alertify.ai.patch;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.ai.api.AiPatchRequest;
import app.alertify.api.error.ConflictException;
import app.alertify.configuration.service.ConfigurationValueValidator;
import app.alertify.jpa.entity.ConfigurationValueType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Transaction boundary for partial AI edits. Domain services still own authorization-independent validation. */
@Service
public class AiPatchService {
    private final AiPatchEngine engine;
    private final JsonMapper json;
    private final Validator validator;
    private final EntityManager entities;
    private final ConfigurationValueValidator configurationValues;

    public AiPatchService(AiPatchEngine engine, JsonMapper json, Validator validator, EntityManager entities, ConfigurationValueValidator configurationValues) {
        this.engine = engine;
        this.json = json;
        this.validator = validator;
        this.entities = entities;
        this.configurationValues = configurationValues;
    }

    @Transactional
    public <Q, R> R patch(long id, AiPatchRequest request, Class<?> entityType, Class<Q> requestType, Supplier<R> read, Function<Q, R> write) {
        if (!validator.validate(request).isEmpty())
            throw new AiPatchException("Invalid PATCH request");

        // Read through the scoped domain service first (e.g. a PIPE tag is not an ALERT tag).
        R current = read.get();
        if (json.valueToTree(current).path("version").longValue() != request.version())
            throw ConflictException.versionConflict("Resource changed; reload it and propose a new PATCH");

        Object owner;
        try {
            owner = entities.find(entityType, id, LockModeType.PESSIMISTIC_WRITE);
            entities.refresh(owner, LockModeType.PESSIMISTIC_WRITE);
        } catch (jakarta.persistence.OptimisticLockException exception) {
            throw ConflictException.versionConflict("Resource changed; reload it and propose a new PATCH");
        }
        current = read.get();
        if (json.valueToTree(current).path("version").longValue() != request.version())
            throw ConflictException.versionConflict("Resource changed; reload it and propose a new PATCH");

        ObjectNode before = project(current, requestType);
        ObjectNode proposed = engine.apply(before, request);
        normalize(proposed);
        normalizeParameters(proposed, owner);
        Q complete;
        try {
            checkShape(proposed, requestType);
            complete = json.treeToValue(proposed, requestType);
        } catch (RuntimeException exception) {
            throw new AiPatchException("PATCH result has an unknown field or incompatible value type");
        }
        if (!validator.validate(complete).isEmpty())
            throw new AiPatchException("PATCH result does not satisfy the complete resource constraints");

        ObjectNode expected = project(complete, requestType);
        write.apply(complete);
        entities.flush();
        R saved = read.get();
        if (json.valueToTree(saved).path("version").longValue() == request.version())
            entities.lock(owner, LockModeType.PESSIMISTIC_FORCE_INCREMENT);

        entities.flush();
        entities.clear();
        saved = read.get();
        ObjectNode actual = project(saved, requestType);
        expected.remove("version");
        actual.remove("version");
        if (!AiPatchEngine.equivalent(expected, actual))
            throw new AiPatchException.Verification();

        return saved;
    }

    public ObjectNode project(Object value, Class<?> requestType) {
        JsonNode source = json.valueToTree(value);
        ObjectNode result = (ObjectNode) projectNode(source, requestType);
        if (result.has("tagIds")) {
            ArrayNode tags = json.createArrayNode();
            JsonNode ids = source.has("tags") ? source.get("tags") : source.path("tagIds");
            java.util.stream.StreamSupport.stream(ids.spliterator(), false)
                    .map(node -> node.isObject() ? node.path("id").longValue() : node.longValue())
                    .sorted().forEach(tags::add);
            result.set("tagIds", tags);
        }
        return result;
    }

    private JsonNode projectNode(JsonNode source, Type type) {
        if (source == null || source.isNull())
            return json.nullNode();

        if (type instanceof ParameterizedType parameter && Collection.class.isAssignableFrom((Class<?>) parameter.getRawType())) {
            ArrayNode result = json.createArrayNode();
            for (JsonNode item : source)
                result.add(projectNode(item, parameter.getActualTypeArguments()[0]));

            if (Set.class.isAssignableFrom((Class<?>) parameter.getRawType())) {
                java.util.List<JsonNode> sorted = new java.util.ArrayList<>();
                result.forEach(sorted::add);
                sorted.sort(java.util.Comparator.comparing(JsonNode::toString));
                result.removeAll();
                sorted.forEach(result::add);
            }
            return result;
        }
        if (type instanceof Class<?> target && target.isRecord()) {
            ObjectNode result = json.createObjectNode();
            for (var component : target.getRecordComponents())
                result.set(component.getName(), projectNode(source.get(component.getName()), component.getGenericType()));

            return result;
        }
        return source.deepCopy();
    }

    private static void checkShape(JsonNode node, Type type) {
        if (node.isNull()) {
            if (type instanceof Class<?> raw && raw.isPrimitive())
                throw new AiPatchException();

            return;
        }
        if (type instanceof ParameterizedType parameter && Collection.class.isAssignableFrom((Class<?>) parameter.getRawType())) {
            if (!node.isArray())
                throw new AiPatchException();

            Set<JsonNode> unique = new HashSet<>();
            for (JsonNode item : node) {
                if (item.isNull() || (Set.class.isAssignableFrom((Class<?>) parameter.getRawType()) && !unique.add(item)))
                    throw new AiPatchException();

                checkShape(item, parameter.getActualTypeArguments()[0]);
            }
            return;
        }
        if (!(type instanceof Class<?> target) || JsonNode.class.isAssignableFrom(target))
            return;

        if (target.isRecord()) {
            if (!node.isObject())
                throw new AiPatchException();

            Set<String> allowed = new HashSet<>();
            for (var field : target.getRecordComponents()) {
                allowed.add(field.getName());
                if (node.has(field.getName()))
                    checkShape(node.get(field.getName()), field.getGenericType());
                else if (field.getType().isPrimitive())
                    throw new AiPatchException();
            }
            if (!allowed.containsAll(node.propertyNames()))
                throw new AiPatchException();

        } else if ((target == boolean.class || target == Boolean.class) ? !node.isBoolean()
                : (target == long.class || target == Long.class || target == Integer.class || target == int.class) ? !node.isIntegralNumber()
                : !node.isString()) {
            throw new AiPatchException();
        }
    }

    private void normalize(ObjectNode value) {
        for (String field : java.util.List.of("name", "description", "cronExpression", "color")) {
            JsonNode node = value.get(field);
            if (node != null && node.isString()) {
                String text = node.stringValue().trim();
                if (field.equals("description") && text.isEmpty())
                    value.putNull(field);
                else
                    value.put(field, field.equals("color") ? text.toUpperCase(Locale.ROOT) : field.equals("cronExpression") && text.isEmpty() ? "-" : text);
            }
        }
        if (value.has("valueType")) {
            if ("BINARY".equals(value.path("valueType").asString()))
                throw new AiPatchException("Binary configuration operations are not available to AI tools");

            try {
                value.set("value", configurationValues.validateAndNormalize(ConfigurationValueType.valueOf(value.path("valueType").asString()), value.get("value")));
            } catch (RuntimeException exception) {
                throw new AiPatchException("PATCH configuration value is invalid for its valueType");
            }
        }
        if (value.has("smartExecutionEnabled")) {
            if (!value.path("smartExecutionEnabled").asBoolean()) {
                value.putNull("smartExecutionIntervalHours");
                value.putNull("smartExecutionPolicy");
                value.put("smartExecutionIntervalSource", "FIXED");
                value.putNull("smartExecutionIntervalConfigurationId");
            } else {
                if (value.path("smartExecutionPolicy").isNull())
                    value.put("smartExecutionPolicy", "ONCE_PER_INTERVAL");

                if ("CONFIGURATION".equals(value.path("smartExecutionIntervalSource").asString()))
                    value.putNull("smartExecutionIntervalHours");
                else
                    value.putNull("smartExecutionIntervalConfigurationId");
            }
        }
        normalizeDuration(value, "finallyTimeout", true, false);
        normalizeDuration(value, "rateLimitWindow", false, true);
        for (String collection : java.util.List.of("steps", "targets")) {
            if (!value.path(collection).isArray())
                continue;

            for (JsonNode node : value.path(collection)) {
                if (!(node instanceof ObjectNode element))
                    continue;

                if (!element.has(collection.equals("steps") ? "timeout" : "busyWaitTimeout"))
                    element.putNull(collection.equals("steps") ? "timeout" : "busyWaitTimeout");

                normalizeDuration(element, collection.equals("steps") ? "timeout" : "busyWaitTimeout", true, false);
                for (JsonNode binding : element.path("bindings")) {
                    if (!(binding instanceof ObjectNode object))
                        continue;

                    for (String field : java.util.List.of("sourceOutputKey", "sourceResultPointer", "valueExpression"))
                        if (object.path(field).isString() && object.path(field).stringValue().isBlank())
                            object.putNull(field);
                }
            }
        }
    }

    private void normalizeDuration(ObjectNode object, String field, boolean hasDefault, boolean seconds) {
        if (!object.has(field))
            return;

        JsonNode value = object.get(field);
        if (value.isNull() && hasDefault) {
            object.set(field, json.valueToTree(java.time.Duration.ofMinutes(30)));
        } else if (value.isString()) {
            try {
                var duration = java.time.Duration.parse(value.stringValue());
                object.set(field, json.valueToTree(seconds ? java.time.Duration.ofSeconds(duration.getSeconds())
                        : java.time.Duration.ofMillis(duration.toMillis())));
            } catch (RuntimeException exception) {
                throw new AiPatchException("PATCH duration is invalid");
            }
        }
    }

    private void normalizeParameters(ObjectNode proposed, Object owner) {
        String entity;
        long templateId;
        if (owner instanceof app.alertify.alerts.model.Alert alert) {
            entity = "AlertTemplateParameterDefinition";
            templateId = alert.getTemplate().getId();
        } else if (owner instanceof app.alertify.procedures.model.Procedure procedure) {
            entity = "ProcedureTemplateParameterDefinition";
            templateId = procedure.getTemplate().getId();
        } else {
            return;
        }
        if (!proposed.path("parameters").isArray())
            throw new AiPatchException("PATCH parameters must be an array");

        var definitions = entities.createQuery("select p.parameterKey, p.defaultValue from " + entity
                + " p where p.template.id = :id order by p.parameterOrder, p.id", Object[].class).setParameter("id", templateId).getResultList();
        java.util.Map<String, JsonNode> supplied = new java.util.LinkedHashMap<>();
        for (JsonNode parameter : proposed.path("parameters"))
            if (supplied.put(parameter.path("parameterKey").asString(), parameter) != null)
                throw new AiPatchException("PATCH contains duplicate parameter keys");

        ArrayNode ordered = json.createArrayNode();
        for (Object[] definition : definitions) {
            String key = (String) definition[0];
            JsonNode parameter = supplied.remove(key);
            if (parameter != null) {
                ordered.add(parameter);
            } else if (definition[1] != null) {
                ObjectNode fallback = json.createObjectNode();
                fallback.put("parameterKey", key);
                fallback.put("source", "TEXT");
                fallback.put("textValue", (String) definition[1]);
                ordered.add(fallback);
            }
        }
        if (!supplied.isEmpty())
            throw new AiPatchException("PATCH contains unknown parameter keys");

        proposed.set("parameters", ordered);
    }
}
