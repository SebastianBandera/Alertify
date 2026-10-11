package app.alertify.codex;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class BrowserExtensionPackageServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final BrowserExtensionPackageService service = new BrowserExtensionPackageService(
            "https://alertify.example/alertify", "http://127.0.0.1:53682/alertify/api/ai/codex/oauth/callback");

    @Test
    void generatesInstanceScopedChromeAndFirefoxPackagesWithoutCredentials() throws Exception {
        Map<String, String> chrome = entries(service.build("chrome").contents());
        Map<String, String> firefox = entries(service.build("firefox").contents());

        assertThat(chrome.keySet()).containsExactlyInAnyOrder(
                "manifest.json", "config.js", "background.js", "content.js", "callback.html", "callback.js", "callback.css");
        assertThat(firefox.keySet()).containsExactlyInAnyOrder(
                "manifest.json", "config.js", "background-firefox.js", "content.js", "callback.html", "callback.js", "callback.css");
        var chromeManifest = JSON.readTree(chrome.get("manifest.json"));
        var firefoxManifest = JSON.readTree(firefox.get("manifest.json"));
        assertThat(chromeManifest.path("manifest_version").intValue()).isEqualTo(3);
        assertThat(chromeManifest.path("background").path("service_worker").stringValue())
                .isEqualTo("background.js");
        assertThat(chromeManifest.path("host_permissions").toString()).contains("127.0.0.1:53682");
        assertThat(chromeManifest.path("web_accessible_resources").get(0).path("matches").toString())
                .contains("http://127.0.0.1:53682/*", "https://*.openai.com/*", "https://*.chatgpt.com/*");
        assertThat(firefoxManifest.path("manifest_version").intValue()).isEqualTo(2);
        assertThat(firefoxManifest.path("background").path("scripts").size()).isEqualTo(2);
        assertThat(firefoxManifest.path("background").path("scripts").get(1).stringValue())
                .isEqualTo("background-firefox.js");
        assertThat(firefoxManifest.path("background").path("persistent").booleanValue()).isTrue();
        assertThat(firefoxManifest.path("host_permissions").isMissingNode()).isTrue();
        assertThat(firefoxManifest.path("permissions").toString())
                .contains("webRequest", "webRequestBlocking", "https://alertify.example/*", "http://127.0.0.1/*")
                .doesNotContain("declarativeNetRequest");
        assertThat(firefox.get("background-firefox.js"))
                .contains("api.webRequest.onBeforeRequest.addListener", "['blocking']")
                .doesNotContain("declarativeNetRequest");
        assertThat(firefoxManifest.path("web_accessible_resources").get(0).stringValue()).isEqualTo("callback.html");
        assertThat(firefoxManifest.path("browser_specific_settings").path("gecko")
                .path("data_collection_permissions").path("required").get(0).stringValue())
                .isEqualTo("authenticationInfo");
        assertThat(chrome.get("config.js"))
                .contains("https://alertify.example/alertify", "http://127.0.0.1:53682/alertify/api/ai/codex/oauth/callback", "\"callbackMatch\":\"http://127.0.0.1/*\"", "\"protocolVersion\":3")
                .doesNotContain("accessToken", "refreshToken", "callbackTicket");
        assertThat(service.instanceId()).hasSize(24);
        assertThat(service.protocolVersion()).isEqualTo(3);
    }

    private static Map<String, String> entries(byte[] archive) throws Exception {
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null)
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
        return entries;
    }
}
