package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertExecutionValue;
import app.alertify.alerts.AlertExecutionValueSource;
import tools.jackson.databind.json.JsonMapper;

class PlaywrightDslValuesTest {
    @Test
    void selectsEveryJsonTypeAndDistinguishesNullFromMissing() {
        var json = JsonMapper.builder().build();
        var value = json.readTree("{\"code\":\"001234\",\"n\":42,\"ok\":true,\"object\":{\"k\":1},\"list\":[1,2],\"nil\":null,\"a/b\":\"escaped\"}");
        assertThat(PlaywrightDslValues.select(value, "/code")).isEqualTo("001234");
        assertThat(PlaywrightDslValues.select(value, "/n")).isEqualTo("42");
        assertThat(PlaywrightDslValues.select(value, "/ok")).isEqualTo("true");
        assertThat(PlaywrightDslValues.select(value, "/object")).isEqualTo("{\"k\":1}");
        assertThat(PlaywrightDslValues.select(value, "/list")).isEqualTo("[1,2]");
        assertThat(PlaywrightDslValues.select(value, "/nil")).isEqualTo("null");
        assertThat(PlaywrightDslValues.select(value, "/a~1b")).isEqualTo("escaped");
        assertThat(PlaywrightDslValues.select(value, "")).isEqualTo(value.toString());
        assertThatThrownBy(() -> PlaywrightDslValues.select(value, "/missing")).hasMessageContaining("does not resolve");
        assertThatThrownBy(() -> PlaywrightDslValues.pointer("/bad~x")).hasMessageContaining("Invalid");
        assertThatThrownBy(() -> PlaywrightDslValues.pointer("/line\n~x")).hasMessageContaining("Invalid");
    }

    @Test
    void callsLazilyReusesVariablesAndRequiresReplayInANewBrowser() throws Exception {
        var commands = PlaywrightPageEngine.parseCommands("CALL PROCEDURE \"Generar OTP\" POINTER \"/code\" AS codigo\nQUERY #otp\nFILL vars.codigo\nFILL vars.codigo");
        var calls = new AtomicInteger();
        List<String> protectedValues = new ArrayList<>();
        String key = PlaywrightDslValues.key("Generar OTP", "/code");
        var context = new AlertExecutionContext("", Map.of(), List.of(new AlertExecutionValue(AlertExecutionValueSource.PROCEDURE, key, "capability")),
                token -> { assertThat(token).isEqualTo("capability"); calls.incrementAndGet(); return "001234"; }, protectedValues::add);
        var browser = mock(PlaywrightPageEngine.BrowserSession.class);
        var state = new PlaywrightPageEngine.ScriptState();
        assertThat(calls).hasValue(0);
        for (var command : commands)
            PlaywrightPageEngine.execute(context, browser, command, 100, state);

        assertThat(calls).hasValue(1);
        assertThat(protectedValues).containsExactly("001234");
        verify(browser, org.mockito.Mockito.times(2)).fill("001234", 100);
        assertThatThrownBy(() -> PlaywrightPageEngine.execute(context, browser, commands.getLast(), 100, new PlaywrightPageEngine.ScriptState())).hasMessageContaining("restart");
    }

    @Test
    void parsesEscapedNamesAndPreparesOnlyDeclaredCredentialFields() {
        var commands = PlaywrightPageEngine.parseCommands("QUERY #user\nFILL SECRET \"Login \\\"temporal\\\"\" POINTER \"/username\"\nQUERY #pass\nFILL SECRET \"Login \\\"temporal\\\"\" POINTER \"/password\"");
        var references = PlaywrightPageEngine.requiredValues("QUERY #user\nFILL SECRET \"Login temporal\" POINTER \"/username\"\nQUERY #pass\nFILL SECRET \"Login temporal\" POINTER \"/password\"");
        assertThat(commands.get(1).first()).isEqualTo("Login \"temporal\"");
        assertThat(PlaywrightPageEngine.parseCommands("QUERY #user\nFILL SECRET\t\"Login\" POINTER \"/username\"").getLast().second()).isEqualTo("/username");
        assertThat(references).hasSize(2);
        assertThat(references.getFirst().key()).isNotEqualTo(references.getLast().key());
        assertThatThrownBy(() -> PlaywrightPageEngine.parseCommands("QUERY #otp\nFILL vars.missing")).hasMessageContaining("earlier CALL");
        assertThatThrownBy(() -> PlaywrightPageEngine.parseCommands("QUERY #otp\nFILL SECRET \"Login\" POINTER \"/other\"")).hasMessageContaining("/username");
    }
}
