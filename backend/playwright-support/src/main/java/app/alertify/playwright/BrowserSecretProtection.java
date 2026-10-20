package app.alertify.playwright;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/** Document-local instrumentation. Password material stays in the browser process. */
final class BrowserSecretProtection {
    private final Page page;
    private final String key = "__alertifyProtection_" + UUID.randomUUID().toString().replace("-", "");
    private final String attribute = "data-alertify-mask-" + UUID.randomUUID().toString().replace("-", "");

    BrowserSecretProtection(Page page) {
        this.page = page;
        page.addInitScript(INSTALL.replace("STATE_KEY", key).replace("MASK_ATTRIBUTE", attribute));
    }

    @SuppressWarnings("unchecked")
    List<String> passwords() {
        List<String> values = new ArrayList<>();
        for (Frame frame : page.frames()) {
            Object result = frame.evaluate("key => { if (!window[key]) throw new Error('Protection unavailable'); return window[key].passwords(); }", key);
            values.addAll((List<String>) result);
        }
        return values;
    }

    List<Locator> componentAndFoldedTextMasks(List<String> base64Values) {
        List<Locator> masks = new ArrayList<>();
        for (Frame frame : page.frames()) {
            frame.evaluate("({key, values}) => { if (!window[key]) throw new Error('Protection unavailable'); window[key].markHosts(values); }", Map.of("key", key, "values", base64Values));
            masks.add(frame.locator("[" + attribute + "]"));
        }
        return masks;
    }

    /**
     * Runs in every new document, including child frames, before application scripts.
     * Keeps the original closed mode; records roots only to identify passwords and the
     * outer host to cover in screenshots. Custom elements are masked conservatively too.
     * This is defensive instrumentation, not a security boundary against hostile scripts.
     */
    private static final String INSTALL = """
            (() => {
              const roots = new Set();
              const nativeAttach = Element.prototype.attachShadow;
              Element.prototype.attachShadow = function(options) {
                const root = Reflect.apply(nativeAttach, this, [options]);
                if (root.mode === 'closed') roots.add(root);
                return root;
              };
              const visit = callback => {
                const seen = new Set();
                const walk = root => {
                  if (seen.has(root)) return;
                  seen.add(root);
                  for (const element of root.querySelectorAll('*')) {
                    callback(element);
                    if (element.shadowRoot) walk(element.shadowRoot);
                  }
                };
                walk(document);
                for (const root of roots) if (root.host.isConnected) walk(root);
              };
              const mark = element => {
                let host = element;
                while (host.getRootNode().host) host = host.getRootNode().host;
                host.setAttribute('MASK_ATTRIBUTE', '');
              };
              Object.defineProperty(window, 'STATE_KEY', {value: {
                passwords() {
                  const values = [];
                  // Read by DOM type, independently of Alertify's configured secret registry.
                  visit(element => { if (element instanceof HTMLInputElement && element.type === 'password' && element.value) values.push(element.value); });
                  return values;
                },
                markHosts(values) {
                  visit(element => element.removeAttribute('MASK_ATTRIBUTE'));
                  for (const root of roots) if (root.host.isConnected) mark(root.host);
                  visit(element => {
                    if (element.localName.includes('-')) mark(element);
                    // Match folded Base64 in text pixels without copying page text back to Java.
                    const text = [...element.childNodes].filter(node => node.nodeType === Node.TEXT_NODE).map(node => node.textContent).join('').replace(/[ \\t\\r\\n]/g, '');
                    if (text && values.some(value => text.includes(value))) mark(element);
                  });
                }
              }});
            })();
            """;
}
