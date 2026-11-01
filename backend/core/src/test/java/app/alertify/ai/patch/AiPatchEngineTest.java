package app.alertify.ai.patch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import app.alertify.ai.api.AiPatchRequest;
import app.alertify.ai.api.AiPatchRequest.Operation;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AiPatchEngineTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final AiPatchEngine engine = new AiPatchEngine(json);

    @Test
    void preservesOmittedFieldsAndChangesNestedJsonWithoutIdentityRequirements() {
        var before = document("""
                {"version":2,"writable":false,"value":{"timeout":30,"steps":[1,2],"a/b":{"~":3}}}
                """);
        var result = engine.apply(before, patch(
                op("replace", "/value/timeout", "60"),
                op("add", "/value/steps/-", "3"),
                op("replace", "/value/a~1b/~0", "4")));
        assertThat(result.path("writable").booleanValue()).isFalse();
        assertThat(result.at("/value/steps").toString()).isEqualTo("[1,2,3]");
        assertThat(result.at("/value/a~1b/~0").intValue()).isEqualTo(4);
        assertThat(before.at("/value/timeout").intValue()).isEqualTo(30);
    }

    @Test
    void supportsExplicitNullFalseZeroAndEmptyString() {
        var result = engine.apply(document("{\"value\":{},\"description\":\"keep\"}"), patch(
                op("replace", "/description", "null"), op("add", "/value/a", "false"),
                op("add", "/value/b", "0"), op("add", "/value/c", "\"\"")));
        assertThat(result.path("description").isNull()).isTrue();
        assertThat(result.path("value").toString()).isEqualTo("{\"a\":false,\"b\":0,\"c\":\"\"}");
    }

    @Test
    void requiresIdentityBeforeEditingAnExistingParameter() {
        var before = document("{\"parameters\":[{\"parameterKey\":\"message\",\"textValue\":\"old\"}]}");
        assertThatThrownBy(() -> engine.apply(before, patch(op("replace", "/parameters/0/textValue", "\"new\""))))
                .isInstanceOf(IllegalArgumentException.class);
        var result = engine.apply(before, patch(op("test", "/parameters/0/parameterKey", "\"message\""),
                op("replace", "/parameters/0/textValue", "\"new\"")));
        assertThat(result.at("/parameters/0/textValue").stringValue()).isEqualTo("new");
    }

    @Test
    void identityFollowsTheElementWhenItMoves() {
        var result = engine.apply(document("{\"steps\":[{\"key\":\"a\"},{\"key\":\"b\"}]}"), patch(
                op("test", "/steps/0/key", "\"a\""), new Operation("move", "/steps/1", null, "/steps/0"),
                op("add", "/steps/1/timeout", "\"PT1S\"")));
        assertThat(result.at("/steps/0/key").stringValue()).isEqualTo("b");
        assertThat(result.at("/steps/1/timeout").stringValue()).isEqualTo("PT1S");
    }

    @Test
    void requiresBothTargetIdentityFieldsAndDoesNotShareTestsAcrossTargets() {
        var before = document("{\"targets\":[{\"type\":\"PIPE\",\"resourceId\":1},{\"type\":\"PIPE\",\"resourceId\":2}]}");
        assertThatThrownBy(() -> engine.apply(before, patch(
                op("test", "/targets/0/type", "\"PIPE\""), op("test", "/targets/0/resourceId", "1"),
                op("test", "/targets/1/resourceId", "2"), op("remove", "/targets/1", null))))
                .isInstanceOf(IllegalArgumentException.class);
        var result = engine.apply(before, patch(op("test", "/targets/1/type", "\"PIPE\""),
                op("test", "/targets/1/resourceId", "2"), op("remove", "/targets/1", null)));
        assertThat(result.path("targets")).hasSize(1);
    }

    @Test
    void requiresStepAndBindingIdentityForNestedBindingChanges() {
        var before = document("{\"steps\":[{\"key\":\"a\",\"bindings\":[{\"targetParameterKey\":\"x\",\"valueExpression\":null}]}]}");
        assertThatThrownBy(() -> engine.apply(before, patch(op("test", "/steps/0/key", "\"a\""),
                op("replace", "/steps/0/bindings/0/valueExpression", "\"new\"")))).isInstanceOf(IllegalArgumentException.class);
        var result = engine.apply(before, patch(op("test", "/steps/0/key", "\"a\""),
                op("test", "/steps/0/bindings/0/targetParameterKey", "\"x\""),
                op("replace", "/steps/0/bindings/0/valueExpression", "\"new\"")));
        assertThat(result.at("/steps/0/bindings/0/valueExpression").stringValue()).isEqualTo("new");
    }

    @Test
    void rejectsInvalidPathsAndNeverMutatesTheOriginalOnFailure() {
        var before = document("{\"version\":2,\"name\":\"keep\",\"value\":[1]}");
        for (String path : List.of("/version", "/id", "/value/01", "/value/2", "/value/~2", "")) {
            assertThatThrownBy(() -> engine.apply(before, patch(op("replace", "/name", "\"changed\""),
                    op("replace", path, "1")))).isInstanceOf(IllegalArgumentException.class);
            assertThat(before.path("name").stringValue()).isEqualTo("keep");
        }
    }

    @Test
    void neverEchoesMalformedSubmittedValues() {
        assertThatThrownBy(() -> engine.apply(document("{\"name\":\"old\"}"), patch(op("replace", "/name", "PRIVATE-MARKER"))))
                .hasMessageNotContaining("PRIVATE-MARKER").hasNoCause();
        assertThatThrownBy(() -> engine.apply(document("{\"name\":\"old\"}"), patch(op("replace", "/name", "\"new\" true"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ObjectNode document(String source) { return (ObjectNode) json.readTree(source); }

    @Test
    void explainsThatSystemJsonPathsStartAtValueWithoutEchoingTheSubmittedValue() {
        assertThatThrownBy(() -> engine.apply(document("{\"value\":{\"days\":3}}"),
                patch(op("replace", "/days", "\"PRIVATE-VALUE\""))))
                .hasMessageContaining("operation 1").hasMessageContaining("/value").hasMessageNotContaining("PRIVATE-VALUE");
    }
    private static AiPatchRequest patch(Operation... operations) { return new AiPatchRequest(2L, List.of(operations)); }
    private static Operation op(String op, String path, String value) { return new Operation(op, path, value, null); }
}
