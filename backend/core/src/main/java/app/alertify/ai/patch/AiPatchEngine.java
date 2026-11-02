package app.alertify.ai.patch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import app.alertify.ai.api.AiPatchRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Applies bounded operations to an isolated editable document, never to a JPA entity. */
@Component
public class AiPatchEngine {
    private final JsonMapper json;

    public AiPatchEngine(JsonMapper json) {
        this.json = json;
    }

    public ObjectNode apply(ObjectNode current, AiPatchRequest request) {
        ObjectNode result = current.deepCopy();
        java.util.Map<JsonNode, Set<String>> tested = new java.util.IdentityHashMap<>();
        int index = 0;
        for (var operation : request.operations()) {
            index++;
            try {
                List<String> path = tokens(operation.path());
                if (path.getFirst().equals("version") || !current.has(path.getFirst()))
                    throw new IllegalArgumentException();

                JsonNode value = null;
                if (Set.of("add", "replace", "test").contains(operation.op())) {
                    if (operation.valueJson() == null || operation.from() != null)
                        throw new IllegalArgumentException();

                    try (var parser = json.createParser(operation.valueJson())) {
                        value = json.readTree(parser);
                        if (value == null || parser.nextToken() != null)
                            throw new IllegalArgumentException();
                    }
                } else if (operation.valueJson() != null) {
                    throw new IllegalArgumentException();
                }
                switch (operation.op()) {
                    case "test" -> {
                        if (!equivalent(at(result, path), value))
                            throw new IllegalArgumentException();

                        rememberIdentity(result, path, tested);
                    }
                    case "add", "replace", "remove" -> {
                        if (operation.from() != null)
                            throw new IllegalArgumentException();

                        guard(result, path, tested, "add".equals(operation.op()));
                        change(result, path, value, operation.op());
                    }
                    case "move" -> {
                        List<String> from = tokens(operation.from());
                        if (!from.getFirst().equals(path.getFirst()) || from.equals(path)
                                || operation.path().startsWith(operation.from() + "/"))
                            throw new IllegalArgumentException();

                        guard(result, from, tested, false);
                        guard(result, path, tested, true);
                        JsonNode moved = at(result, from);
                        change(result, from, null, "remove");
                        change(result, path, moved, "add");
                    }
                    default -> throw new IllegalArgumentException();
                }
            } catch (RuntimeException exception) {
                // Parser and conversion errors can contain submitted values. Never attach their cause.
                String roots = current.propertyNames().stream().filter(name -> !name.equals("version")).sorted()
                        .map(name -> "/" + name).collect(java.util.stream.Collectors.joining(", "));
                throw new AiPatchException("Invalid PATCH operation " + index + ". Paths address the editable definition, not the embedded value. Allowed roots: "
                        + roots + ". Check the JSON Pointer, identity test, operation and value type.");
            }
        }
        return result;
    }

    public static boolean equivalent(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber())
            return left.decimalValue().compareTo(right.decimalValue()) == 0;

        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size())
                return false;

            for (int i = 0; i < left.size(); i++)
                if (!equivalent(left.get(i), right.get(i)))
                    return false;

            return true;
        }
        if (left.isObject() && right.isObject()) {
            if (left.size() != right.size())
                return false;

            for (String name : left.propertyNames())
                if (!right.has(name) || !equivalent(left.get(name), right.get(name)))
                    return false;

            return true;
        }
        return left.equals(right);
    }

    private static List<String> tokens(String pointer) {
        if (pointer == null || !pointer.startsWith("/") || pointer.length() == 1)
            throw new IllegalArgumentException();

        List<String> result = new ArrayList<>();
        for (String part : pointer.substring(1).split("/", -1)) {
            if (part.matches(".*~(?![01]).*"))
                throw new IllegalArgumentException();

            result.add(part.replace("~1", "/").replace("~0", "~"));
        }
        return result;
    }

    private static JsonNode at(JsonNode document, List<String> path) {
        JsonNode value = document;
        for (String token : path) {
            value = value.isArray() ? value.get(arrayIndex(token, value.size(), false)) : value.get(token);
            if (value == null)
                throw new IllegalArgumentException();
        }
        return value;
    }

    private static int arrayIndex(String token, int size, boolean add) {
        if (add && token.equals("-"))
            return size;

        if (!token.matches("0|[1-9][0-9]*"))
            throw new IllegalArgumentException();

        int index = Integer.parseInt(token);
        if (index < 0 || index > size || (!add && index == size))
            throw new IllegalArgumentException();

        return index;
    }

    private static void change(ObjectNode document, List<String> path, JsonNode value, String op) {
        JsonNode parent = path.size() == 1 ? document : at(document, path.subList(0, path.size() - 1));
        String key = path.getLast();
        if (parent instanceof ArrayNode array) {
            int index = arrayIndex(key, array.size(), op.equals("add"));
            switch (op) {
                case "add" -> array.insert(index, value);
                case "replace" -> array.set(index, value);
                case "remove" -> array.remove(index);
                default -> throw new IllegalArgumentException();
            }
        } else if (parent instanceof ObjectNode object) {
            if (!op.equals("add") && !object.has(key))
                throw new IllegalArgumentException();

            if (op.equals("remove"))
                object.remove(key);
            else
                object.set(key, value);
        } else {
            throw new IllegalArgumentException();
        }
    }

    private static Set<String> identities(String collection) {
        return switch (collection) {
            case "parameters" -> Set.of("parameterKey");
            case "steps" -> Set.of("key");
            case "targets" -> Set.of("type", "resourceId");
            case "bindings" -> Set.of("targetParameterKey");
            default -> Set.of();
        };
    }

    private static void rememberIdentity(ObjectNode document, List<String> path, java.util.Map<JsonNode, Set<String>> tested) {
        if (path.size() < 3)
            return;

        List<String> parentPath = path.subList(0, path.size() - 1);
        String collection = path.get(path.size() - 3);
        Set<String> keys = identities(collection);
        if (keys.contains(path.getLast())) {
            JsonNode parent = at(document, parentPath);
            tested.computeIfAbsent(parent, ignored -> new HashSet<>()).add(path.getLast());
        }
    }

    private static void guard(ObjectNode document, List<String> path, java.util.Map<JsonNode, Set<String>> tested, boolean inserting) {
        // Values inside a configuration JSON document are arbitrary application data.
        if (path.getFirst().equals("value"))
            return;

        for (int i = 0; i < path.size(); i++) {
            if (identities(path.get(i)).isEmpty())
                continue;

            JsonNode collection = at(document, path.subList(0, i + 1));
            if (!collection.isArray())
                continue;

            if (i + 1 == path.size()) {
                for (JsonNode element : collection)
                    if (!tested.getOrDefault(element, Set.of()).containsAll(identities(path.get(i))))
                        throw new IllegalArgumentException();

            } else if (!(inserting && i + 2 == path.size())) {
                JsonNode element = at(document, path.subList(0, i + 2));
                if (!tested.getOrDefault(element, Set.of()).containsAll(identities(path.get(i))))
                    throw new IllegalArgumentException();
            }
        }
    }
}
