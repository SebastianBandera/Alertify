package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WizardScriptProposalTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void onlyRestartAcceptsANewHttpUrlAndReplayEnginesRemainExplicit() {
        assertThat(WizardScriptProposal.url(json.readTree("\"fixture.test/start\""))).isEqualTo("https://fixture.test/start");
        for (String value : new String[] { "javascript:alert(1)", "https://user:password@fixture.test/", "" })
            assertThatThrownBy(() -> WizardScriptProposal.url(json.valueToTree(value))).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> WizardScriptProposal.action(json.readTree("{\"type\":\"REPLAY\",\"url\":\"https://fixture.test\"}"))).hasMessageContaining("Only RESTART");
        assertThatThrownBy(() -> WizardScriptProposal.action(json.readTree("{\"type\":\"REPLAY\",\"untilLine\":4294967297}"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WizardScriptProposal.action(json.readTree("{\"type\":\"REPLAY\",\"engines\":[\"chromium\",\"chromium\"]}"))).hasMessageContaining("engines");
        var action = WizardScriptProposal.action(json.readTree("{\"type\":\"REPLAY\",\"engines\":[\"firefox\",\"webkit\"],\"untilLine\":24}"));
        assertThat(action.engines()).containsExactly("firefox", "webkit");
        assertThat(action.untilLine()).isEqualTo(24);
    }
}
