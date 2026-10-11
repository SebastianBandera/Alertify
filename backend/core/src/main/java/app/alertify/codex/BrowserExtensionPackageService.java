package app.alertify.codex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class BrowserExtensionPackageService {

    private static final List<String> COMMON_FILES = List.of("content.js", "callback.html", "callback.js", "callback.css");
    private static final int PROTOCOL_VERSION = 3;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String publicBaseUrl;
    private final String pageOrigin;
    private final String callbackUri;
    private final String callbackOrigin;
    private final String callbackMatch;
    private final String relayUrl;
    private final String instanceId;

    public BrowserExtensionPackageService(@Value("${ai.public-url}") String publicUrl, @Value("${ai.oauth.extension-callback-uri}") String extensionCallbackUri) {
        URI publicUri = absoluteHttpUri(publicUrl, "AI public URL");
        URI callback = absoluteHttpUri(extensionCallbackUri, "AI extension callback URI");
        if (!"127.0.0.1".equals(callback.getHost()))
            throw new IllegalStateException("The AI extension callback URI must use 127.0.0.1");

        publicBaseUrl = withoutTrailingSlash(publicUri.toString());
        pageOrigin = publicUri.getScheme() + "://" + publicUri.getRawAuthority();
        callbackUri = callback.toString();
        callbackOrigin = callback.getScheme() + "://" + callback.getRawAuthority();
        callbackMatch = callback.getScheme() + "://" + callback.getHost() + "/*";
        relayUrl = publicBaseUrl + "/api/ai/codex/oauth/callback/relay";
        instanceId = digest(publicBaseUrl + "\n" + callbackUri).substring(0, 24);
    }

    public ExtensionPackage build(String browserName) {
        Browser browser = Browser.parse(browserName);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                add(zip, "manifest.json", manifest(browser));
                add(zip, "config.js", config(browser));
                add(zip, browser.backgroundFile, resource(browser.backgroundFile));
                for (String file : COMMON_FILES)
                    add(zip, file, resource(file));
            }
            String host = URI.create(publicBaseUrl).getHost().replaceAll("[^A-Za-z0-9.-]", "-");
            return new ExtensionPackage("alertify-codex-" + browser.fileName + "-" + host + ".zip", bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("The browser extension package could not be generated", exception);
        }
    }

    public String instanceId() {
        return instanceId;
    }

    public String extensionCallbackUri() {
        return callbackUri;
    }

    public int protocolVersion() {
        return PROTOCOL_VERSION;
    }

    private String manifest(Browser browser) {
        ObjectNode manifest = JSON.createObjectNode();
        // Firefox temporary MV3 installs do not reliably grant required host permissions because they skip the install prompt.
        manifest.put("manifest_version", browser == Browser.CHROME ? 3 : 2);
        manifest.put("name", "Alertify Codex - " + URI.create(publicBaseUrl).getHost());
        manifest.put("version", "1.0.2");
        manifest.put("description", "Completes the Codex OAuth callback for this Alertify instance.");
        if (browser == Browser.CHROME) {
            manifest.set("permissions", array("declarativeNetRequestWithHostAccess", "storage"));
            manifest.set("host_permissions", array(pageOrigin + "/*", callbackOrigin + "/*"));
        } else {
            manifest.set("permissions", array("webRequest", "webRequestBlocking", "storage", pageOrigin + "/*", callbackMatch));
        }

        ObjectNode background = manifest.putObject("background");
        if (browser == Browser.CHROME)
            background.put("service_worker", browser.backgroundFile);
        else {
            background.set("scripts", array("config.js", browser.backgroundFile));
            background.put("persistent", true);
        }

        ObjectNode content = JSON.createObjectNode();
        content.set("matches", array(contentMatch()));
        content.set("js", array("config.js", "content.js"));
        content.put("run_at", "document_start");
        manifest.set("content_scripts", JSON.createArrayNode().add(content));

        if (browser == Browser.CHROME) {
            ObjectNode accessible = JSON.createObjectNode();
            accessible.set("resources", array("callback.html", "callback.js", "callback.css", "config.js"));
            accessible.set("matches", array(callbackOrigin + "/*", "https://*.openai.com/*", "https://*.chatgpt.com/*"));
            manifest.set("web_accessible_resources", JSON.createArrayNode().add(accessible));
        } else {
            manifest.set("web_accessible_resources", array("callback.html", "callback.js", "callback.css", "config.js"));
        }

        if (browser == Browser.FIREFOX) {
            ObjectNode gecko = manifest.putObject("browser_specific_settings").putObject("gecko");
            gecko.put("id", "alertify-codex-" + instanceId + "@alertify.local");
            gecko.put("strict_min_version", "128.0");
            gecko.putObject("data_collection_permissions").set("required", array("authenticationInfo"));
        }
        return JSON.writeValueAsString(manifest);
    }

    private String config(Browser browser) {
        ObjectNode config = JSON.createObjectNode();
        config.put("instanceId", instanceId);
        config.put("protocolVersion", PROTOCOL_VERSION);
        config.put("browser", browser.name());
        config.put("publicBaseUrl", publicBaseUrl);
        config.put("pageOrigin", pageOrigin);
        config.put("relayUrl", relayUrl);
        config.put("callbackUri", callbackUri);
        config.put("callbackMatch", callbackMatch);
        config.put("callbackRegex", "^" + regexEscape(callbackUri) + "(?:\\?(.*))?$");
        return "globalThis.ALERTIFY_CODEX_CONFIG = Object.freeze(" + JSON.writeValueAsString(config) + ");\n";
    }

    private String contentMatch() {
        URI uri = URI.create(publicBaseUrl);
        String path = uri.getRawPath();
        return pageOrigin + (path == null || path.isBlank() || "/".equals(path) ? "/*" : withoutTrailingSlash(path) + "/*");
    }

    private static ArrayNode array(String... values) {
        ArrayNode array = JSON.createArrayNode();
        for (String value : values)
            array.add(value);

        return array;
    }

    private static String resource(String name) throws IOException {
        try (InputStream input = new ClassPathResource("ai-extension/" + name).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void add(ZipOutputStream zip, String name, String contents) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(contents.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static URI absoluteHttpUri(String value, String label) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(label + " must be an absolute HTTP URL", exception);
        }
        if (!uri.isAbsolute() || uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())))
            throw new IllegalStateException(label + " must be an absolute HTTP URL");

        return uri;
    }

    private static String regexEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() * 2);
        for (char character : value.toCharArray()) {
            if ("\\.^$|?*+()[]{}".indexOf(character) >= 0)
                escaped.append('\\');

            escaped.append(character);
        }
        return escaped.toString();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record ExtensionPackage(String fileName, byte[] contents) {
    }

    private enum Browser {
        CHROME("chrome", "background.js"), FIREFOX("firefox", "background-firefox.js");

        private final String fileName;
        private final String backgroundFile;

        Browser(String fileName, String backgroundFile) {
            this.fileName = fileName;
            this.backgroundFile = backgroundFile;
        }

        private static Browser parse(String value) {
            try {
                return Browser.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Unsupported browser extension: " + value);
            }
        }
    }
}
