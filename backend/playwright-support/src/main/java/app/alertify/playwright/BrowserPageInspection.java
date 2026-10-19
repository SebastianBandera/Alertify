package app.alertify.playwright;

import java.util.Map;
import java.util.UUID;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/** All browser access, including callbacks, stays on the owning Playwright thread. */
final class BrowserPageInspection {
    private final Page page;
    private final String key = "__alertifyInspection_" + UUID.randomUUID().toString().replace("-", "");

    BrowserPageInspection(Page page, Runnable captureIfDue) {
        this.page = page;
        String binding = "__alertifyCapture_" + UUID.randomUUID().toString().replace("-", "");
        page.exposeBinding(binding, (source, arguments) -> {
            if (source.frame() == page.mainFrame())
                captureIfDue.run();

            return null;
        });
        page.addInitScript(INSTALL.replace("STATE_KEY", key).replace("CAPTURE_BINDING", binding));
    }

    void beginFrame(long frameId) {
        page.evaluate("({key, id}) => window[key]?.beginFrame(id)", Map.of("key", key, "id", Long.toString(frameId)));
    }

    void finishFrame(long frameId) {
        page.evaluate("({key, id}) => window[key]?.finishFrame(id)", Map.of("key", key, "id", Long.toString(frameId)));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> select(long frameId, double x, double y) {
        return (Map<String, Object>) page.evaluate("({key, id, x, y}) => window[key]?.select(id, x, y) ?? {error: 'STALE_SELECTION'}", Map.of("key", key, "id", Long.toString(frameId), "x", x, "y", y));
    }

    static String selector(Locator element) {
        return (String) element.evaluate(SELECTOR);
    }

    /**
     * Builds a selector that uniquely identifies the supplied element in the current document.
     * Prefers an ID or data-testid, then falls back to a path from body with sibling indexes.
     * Shared by direct locator inspection and selection from a captured frame.
     */
    private static final String SELECTOR_BODY = """
            // The document background is not a useful target for the wizard.
            if (!element || element === document.body || element === document.documentElement) return null;
            // Verify identity as well as uniqueness: duplicate IDs can otherwise select another element.
            const unique = selector => {
              try { const matches = document.querySelectorAll(selector); return matches.length === 1 && matches[0] === element; }
              catch { return false; }
            };
            if (element.id) {
              const selector = '#' + CSS.escape(element.id);
              if (unique(selector)) return selector;
            }
            const testId = element.getAttribute('data-testid');
            if (testId) {
              const selector = '[data-testid=' + JSON.stringify(testId) + ']';
              if (unique(selector)) return selector;
            }
            // Build a structural fallback when the page does not provide a unique stable attribute.
            const segments = [];
            let current = element;
            while (current && current !== document.body) {
              let selector = current.tagName.toLowerCase();
              const siblings = current.parentElement ? [...current.parentElement.children].filter(e => e.tagName === current.tagName) : [];
              if (siblings.length > 1) selector += ':nth-of-type(' + (siblings.indexOf(current) + 1) + ')';
              segments.unshift(selector);
              current = current.parentElement;
            }
            if (current !== document.body || !segments.length) return null;
            const selector = 'body > ' + segments.join(' > ');
            return unique(selector) ? selector : null;
            """;
    // Adapt the shared body to the function expected by Locator.evaluate().
    private static final String SELECTOR = "element => {" + SELECTOR_BODY + "}";

    /**
     * Installs inspection state on each new main document before the page's own scripts run.
     * Records DOM geometry and identity around each screenshot, then resolves image coordinates
     * only while the captured target and region remain unchanged. Records stay inside the page.
     * A periodic callback requests captures through Java on the owning Playwright thread.
     * The page can mutate this state, so Java must still validate returned selection data.
     */
    private static final String INSTALL = """
            (() => {
              // Each document owns its state; subframes and repeated installation are skipped.
              if (window !== window.top || window.STATE_KEY) return;
              const frames = new Map();
              const selectorFor = element => { SELECTOR_BODY };
              // Coordinates use the viewport, matching elementFromPoint() and screenshot clicks.
              const box = element => {
                const r = element.getBoundingClientRect();
                return [r.x, r.y, r.width, r.height];
              };
              const contains = (r, x, y) => x >= r[0] && y >= r[1] && x < r[0] + r[2] && y < r[1] + r[3];
              const sameBox = (a, b) => a.every((value, index) => Math.abs(value - b[index]) < 0.5);
              const signature = element => {
                const style = getComputedStyle(element);
                const text = [...element.childNodes].filter(node => node.nodeType === Node.TEXT_NODE).map(node => node.textContent).join('');
                // Control values are deliberately absent. These records never leave the page.
                return JSON.stringify([element.tagName, ...['id', 'data-testid', 'role', 'name', 'type', 'href', 'aria-label', 'aria-labelledby', 'title', 'disabled'].map(name => element.getAttribute(name)), text,
                  style.display, style.visibility, style.opacity, style.zIndex, style.position, style.transform, style.pointerEvents, style.clipPath, style.overflow]);
              };
              const unchanged = record => record.element.isConnected && sameBox(record.box, box(record.element)) && record.signature === signature(record.element);
              window.STATE_KEY = {
                beginFrame(id) {
                  // Snapshot visible DOM candidates immediately before taking the image.
                  const entries = [];
                  for (const element of document.querySelectorAll('body *')) {
                    const rect = box(element);
                    if (rect[2] <= 0 || rect[3] <= 0 || rect[0] >= innerWidth || rect[1] >= innerHeight || rect[0] + rect[2] <= 0 || rect[1] + rect[3] <= 0) continue;
                    entries.push({element, box: rect, signature: signature(element), stable: false});
                    // Reject selection on oversized documents rather than keep an incomplete map.
                    if (entries.length > 5000) { entries.length = 0; break; }
                  }
                  frames.set(id, {entries, scrollX, scrollY, width: innerWidth, height: innerHeight});
                  // Retain a bounded history for clicks on recently displayed frames.
                  while (frames.size > 10) frames.delete(frames.keys().next().value);
                },
                finishFrame(id) {
                  // Changes during screenshot production invalidate the affected candidates.
                  const frame = frames.get(id);
                  if (frame) for (const record of frame.entries) record.stable = unchanged(record);
                },
                select(id, x, y) {
                  const frame = frames.get(id);
                  // A scroll, resize or invalid point breaks the correspondence with the image.
                  if (!frame || frame.scrollX !== scrollX || frame.scrollY !== scrollY || frame.width !== innerWidth || frame.height !== innerHeight || !Number.isFinite(x) || !Number.isFinite(y) || x < 0 || y < 0 || x >= innerWidth || y >= innerHeight) return {error: 'STALE_SELECTION'};
                  // Check the captured region even if the old target moved away from the point.
                  for (const candidate of frame.entries) {
                    if (contains(candidate.box, x, y) && (!candidate.stable || !unchanged(candidate))) return {error: 'STALE_SELECTION'};
                  }
                  // Resolve the point to its nearest interactive ancestor without activating it.
                  const hit = document.elementFromPoint(x, y);
                  const element = hit?.closest('button,a,input,textarea,select,[role]') || hit;
                  if (!element || element === document.body || element === document.documentElement) return {error: 'NO_SELECTION'};
                  const record = frame.entries.find(candidate => candidate.element === element);
                  if (!record || !record.stable || !unchanged(record)) return {error: 'STALE_SELECTION'};
                  const selector = selectorFor(element);
                  return selector ? {selector} : {error: 'NO_SELECTION'};
                }
              };
              const capture = window.CAPTURE_BINDING;
              // Request frames while Playwright waits for page events; Java throttles capture work.
              // Ignore callback rejections when navigation or session shutdown removes the binding.
              setInterval(() => { capture().catch(() => {}); }, 200);
            })();
            """.replace("SELECTOR_BODY", SELECTOR_BODY);
}
