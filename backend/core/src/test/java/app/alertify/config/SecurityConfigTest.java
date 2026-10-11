package app.alertify.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

class SecurityConfigTest {

    private static final String WEB_ORIGIN = "https://alertify.example";
    private final CorsConfigurationSource source = new SecurityConfig().corsConfigurationSource(WEB_ORIGIN);

    @Test
    void allowsBrowserExtensionsOnlyForCodexCallbackRelay() {
        CorsConfiguration relay = configuration("/api/ai/codex/oauth/callback/relay");

        assertThat(relay.checkOrigin(WEB_ORIGIN)).isEqualTo(WEB_ORIGIN);
        assertThat(relay.checkOrigin("chrome-extension://abcdefghijklmnop")).isEqualTo("chrome-extension://abcdefghijklmnop");
        assertThat(relay.checkOrigin("moz-extension://12345678-abcd-1234-abcd-123456789abc")).isEqualTo("moz-extension://12345678-abcd-1234-abcd-123456789abc");
        assertThat(relay.checkOrigin("https://attacker.example")).isNull();

        CorsConfiguration regularApi = configuration("/api/ai/module");
        assertThat(regularApi.checkOrigin(WEB_ORIGIN)).isEqualTo(WEB_ORIGIN);
        assertThat(regularApi.checkOrigin("chrome-extension://abcdefghijklmnop")).isNull();
        assertThat(regularApi.checkOrigin("moz-extension://12345678-abcd-1234-abcd-123456789abc")).isNull();
    }

    private CorsConfiguration configuration(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", path);
        CorsConfiguration configuration = source.getCorsConfiguration(request);
        assertThat(configuration).isNotNull();
        return configuration;
    }
}
