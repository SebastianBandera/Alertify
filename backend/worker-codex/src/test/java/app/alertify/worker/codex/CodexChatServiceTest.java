package app.alertify.worker.codex;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import tools.jackson.databind.json.JsonMapper;

class CodexChatServiceTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    @EnabledIfEnvironmentVariable(named = "ALERTIFY_AI_SCHEMA_EVIDENCE_DIR", matches = ".+")
    @SuppressWarnings("unchecked")
    void actualAdministrativeSchemasRetainNullPreservationInStrictMode() throws Exception {
        Path directory = Path.of(System.getenv("ALERTIFY_AI_SCHEMA_EVIDENCE_DIR"));
        for (String file : List.of("alert-update-schema.json", "alert-create-schema.json", "procedure-update-schema.json", "procedure-create-schema.json", "pipe-update-schema.json")) {
            Map<String, Object> schema = json.readValue(Files.readString(directory.resolve(file)), Map.class);
            CodexChatService.strictSchema(schema);
            var strict = json.readTree(json.writeValueAsString(schema));
            List<String> nullable = file.equals("alert-update-schema.json")
                    ? List.of("persistentIssues", "smartExecutionEnabled", "smartExecutionIntervalHours", "smartExecutionPolicy")
                    : file.startsWith("pipe") ? List.of("sourceOutputKey", "sourceResultPointer", "valueExpression") : List.of();
            for (String property : nullable) {
                var value = schemaProperty(strict, property);
                assertThat(value.path("anyOf").isArray()).as("%s in %s", property, file).isTrue();
                assertThat(value.path("anyOf").get(1).path("type").stringValue()).isEqualTo("null");
            }
            if (!file.startsWith("pipe")) {
                for (String property : List.of("textValue", "configurationId", "secretId", "procedureId")) {
                    var value = schemaProperty(strict, property);
                    assertThat(value.path("anyOf").isArray()).as("%s in %s", property, file).isTrue();
                    assertThat(value.path("anyOf").get(1).path("type").stringValue()).isEqualTo("null");
                }
                if (file.startsWith("procedure"))
                    assertThat(schemaProperty(strict, "pipeId").path("anyOf").get(1).path("type").stringValue()).isEqualTo("null");

                assertThat(schemaProperty(strict, "parameterKey").has("anyOf")).isFalse();
                assertThat(schemaProperty(strict, "source").has("anyOf")).isFalse();
            }
            Files.writeString(directory.resolve("strict-" + file), json.writeValueAsString(schema));
        }
    }

    private static tools.jackson.databind.JsonNode schemaProperty(tools.jackson.databind.JsonNode schema, String name) {
        var properties = schema.path("properties");
        if (properties.has(name))
            return properties.get(name);

        for (var child : schema) {
            var found = schemaProperty(child, name);
            if (!found.isMissingNode())
                return found;

        }
        return tools.jackson.databind.node.MissingNode.getInstance();
    }

    @SuppressWarnings("unchecked")
    @Test
    void convertsOptionalAndNestedPropertiesToStrictSchema() {
        Map<String, Object> schema = json.readValue("""
                {
                  "type": "object",
                  "properties": {
                    "name": {"type": "string"},
                    "options": {
                      "type": "object",
                      "properties": {
                        "enabled": {"type": "boolean"}
                      }
                    }
                  },
                  "required": ["name"]
                }
                """, Map.class);

        CodexChatService.strictSchema(schema);

        assertThat(schema.get("additionalProperties")).isEqualTo(false);
        assertThat((List<String>) schema.get("required")).containsExactly("name", "options");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties.get("name")).isEqualTo(Map.of("type", "string"));
        Map<String, Object> optionalOptions = (Map<String, Object>) properties.get("options");
        List<Map<String, Object>> alternatives = (List<Map<String, Object>>) optionalOptions.get("anyOf");
        assertThat(alternatives.get(1)).isEqualTo(Map.of("type", "null"));
        assertThat(alternatives.getFirst().get("additionalProperties")).isEqualTo(false);
        assertThat((List<String>) alternatives.getFirst().get("required")).containsExactly("enabled");
    }

    @SuppressWarnings("unchecked")
    @Test
    void buildsResponsesLiteRequestsWithDeveloperInstructionsAndNamespacedTools() {
        List<Object> history = List.of(Map.of("role", "user", "content", "hello"));
        List<Object> tools = List.of(Map.of("type", "function", "name", "sample", "parameters", Map.of("type", "object")));

        Map<String, Object> request = CodexChatService.requestBody("model", "low", "instructions", history, tools, true, false);

        assertThat(request).doesNotContainKeys("instructions", "tools");
        assertThat(request.get("stream")).isEqualTo(true);
        assertThat(request.get("include")).isEqualTo(List.of("reasoning.encrypted_content"));
        assertThat((Map<String, Object>) request.get("reasoning")).containsEntry("context", "all_turns");
        List<Map<String, Object>> input = (List<Map<String, Object>>) request.get("input");
        assertThat(input).hasSize(3);
        assertThat(input.get(0)).containsEntry("type", "additional_tools").containsEntry("role", "developer");
        List<Map<String, Object>> additionalTools = (List<Map<String, Object>>) input.get(0).get("tools");
        assertThat(additionalTools.getFirst()).containsEntry("type", "namespace").containsEntry("name", "functions")
                .containsEntry("tools", tools);
        assertThat(input.get(1)).containsEntry("type", "message").containsEntry("role", "developer");
    }

    @SuppressWarnings("unchecked")
    @Test
    void buildsStandardRequestsWithTopLevelTools() {
        List<Object> tools = List.of(Map.of("type", "function", "name", "sample"));

        Map<String, Object> request = CodexChatService.requestBody("model", "low", "instructions", List.of(), tools, false, false);

        assertThat(request).containsEntry("tools", tools).containsEntry("stream", true);
        assertThat((Map<String, Object>) request.get("reasoning")).doesNotContainKey("context");
    }

    @SuppressWarnings("unchecked")
    @Test
    void groupsPublicChatGptToolsInATopLevelNamespace() {
        List<Object> tools = List.of(Map.of("type", "function", "name", "sample"));

        Map<String, Object> request = CodexChatService.requestBody("model", "low", "instructions", List.of(), tools, false, true);

        List<Map<String, Object>> namespaces = (List<Map<String, Object>>) request.get("tools");
        assertThat(namespaces).singleElement().satisfies(namespace -> assertThat(namespace)
                .containsEntry("type", "namespace").containsEntry("name", "functions").containsEntry("tools", tools));
        assertThat((Map<String, Object>) request.get("reasoning")).doesNotContainKey("context");
    }

    @Test
    void extractsAssistantTextAndRefusalsFromResponseMessages() {
        var response = json.readTree("""
                {
                  "output": [{
                    "type": "message",
                    "content": [
                      {"type": "output_text", "text": "first"},
                      {"type": "refusal", "refusal": "second"}
                    ]
                  }]
                }
                """);
        String output = CodexChatService.outputText(response);

        assertThat(output).isEqualTo("firstsecond");
        assertThat(CodexChatService.outputShape(response.path("output"))).isEqualTo("message[output_text, refusal]");
    }
}
