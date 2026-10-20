package app.alertify.worker.codex;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class CodexMemoryToolsTest {
    @Test
    void disablingRemovesPreviouslyLoadedMemoryToolsAndGroupWithoutDestroyingTheEnabledCatalog() {
        List<Object> tools = List.of(
                Map.of("name", "alertify_memory_search"), Map.of("name", "alertify_memory_save"), Map.of("name", "alertify_memory_delete"),
                Map.of("name", "alertify_configuration_get"),
                Map.of("name", "alertify_load_tool_group", "parameters", Map.of("properties", Map.of("group", Map.of("type", "string", "enum", List.of("ALERTS", "MEMORY"))))));
        var json = JsonMapper.builder().build();
        var disabled = CodexChatService.memoryTools(tools, false);
        assertThat(disabled).hasSize(2);
        assertThat(json.writeValueAsString(disabled)).contains("alertify_configuration_get", "ALERTS").doesNotContain("alertify_memory_", "MEMORY");
        assertThat(json.writeValueAsString(CodexChatService.memoryTools(tools, true))).contains("alertify_memory_search", "MEMORY");
    }
}
