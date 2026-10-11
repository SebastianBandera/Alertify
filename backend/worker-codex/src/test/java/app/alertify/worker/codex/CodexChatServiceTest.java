package app.alertify.worker.codex;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class CodexChatServiceTest {

    private final JsonMapper json = JsonMapper.builder().build();

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
