package app.alertify.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AiConversationContextTest {

    @Test
    void carriesAndRestoresTheTrustedConversationLocale() {
        assertThat(AiConversationContext.currentId()).isNull();
        assertThat(AiConversationContext.currentLocale()).isEqualTo("en");

        String observed = AiConversationContext.callWith(17L, "es-UY", () -> {
            assertThat(AiConversationContext.currentId()).isEqualTo(17L);
            assertThat(AiConversationContext.currentLocale()).isEqualTo("es-UY");
            return AiConversationContext.callWith(18L, "en", AiConversationContext::currentLocale);
        });

        assertThat(observed).isEqualTo("en");
        assertThat(AiConversationContext.currentId()).isNull();
        assertThat(AiConversationContext.currentLocale()).isEqualTo("en");
    }

    @Test
    void rejectsBlankLocales() {
        assertThatThrownBy(() -> AiConversationContext.callWith(17L, " ", () -> null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
