package app.alertify.playwright;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.JSHandle;
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

    Map<String, Object> select(long frameId, double x, double y) {
        Map<String, Object> point = Map.of("key", key, "id", Long.toString(frameId), "x", x, "y", y);
        JSHandle selection = page.evaluateHandle("({key, id, x, y}) => window[key]?.select(id, x, y) ?? {error: 'STALE_SELECTION'}", point);
        try {
            JSHandle error = selection.getProperty("error");
            try {
                if (error.jsonValue() instanceof String code)
                    return Map.of("error", code);

            } finally { error.dispose(); }
            JSHandle target = selection.getProperty("element");
            try {
                ElementHandle element = target.asElement();
                if (element == null)
                    return Map.of("error", "NO_SELECTION");

                String selector = selector(page, element);
                // Resolving candidates crosses browser calls; recheck the captured identity afterwards.
                boolean unchanged = (boolean) selection.evaluate("(selection, {key, id, x, y}) => window[key]?.select(id, x, y)?.element === selection.element", point);
                if (!unchanged)
                    return Map.of("error", "STALE_SELECTION");

                return selector == null ? Map.of("error", "NO_SELECTION") : Map.of("selector", selector);
            } finally { target.dispose(); }
        } finally { selection.dispose(); }
    }

    static String selector(Locator element) {
        JSHandle target = element.evaluateHandle("element => element");
        try { return selector(element.page(), target.asElement()); }
        finally { target.dispose(); }
    }

    @SuppressWarnings("unchecked")
    private static String selector(Page page, ElementHandle element) {
        if (element == null)
            return null;

        List<String> candidates = (List<String>) element.evaluate(CANDIDATES);
        for (String candidate : candidates) {
            Locator matches = page.locator(candidate).filter(new Locator.FilterOptions().setVisible(true));
            if (matches.count() == 1 && (boolean) matches.evaluate("(match, target) => match === target", element))
                return candidate;

        }
        return null;
    }

    /**
     * Produces candidates in preference order, without reading input values. Playwright validates
     * visibility, uniqueness and DOM identity, including its own text-selector extensions.
     * Both observations and captured-frame selections use this same resolution path.
     */
    private static final String CANDIDATES = """
            element => {
            // The document background is not a useful target for the wizard.
            if (!element || !element.isConnected || element === document.body || element === document.documentElement) return [];
            const candidates = new Set();
            const tag = element.tagName.toLowerCase();
            const attribute = (name, value) => '[' + name + '=' + JSON.stringify(value) + ']';
            const anchors = node => {
              const result = [];
              if (node.id) result.push('#' + CSS.escape(node.id));
              const testId = node.getAttribute('data-testid');
              if (testId) result.push(attribute('data-testid', testId));
              return result;
            };
            for (const anchor of anchors(element)) candidates.add(anchor);
            const semantic = [];
            const ariaLabel = element.getAttribute('aria-label');
            if (ariaLabel) semantic.push(tag + attribute('aria-label', ariaLabel));
            // Text is useful for buttons and links, but field values must never become selectors.
            if (!element.matches('input,textarea,select,[contenteditable]') && !element.closest('input,textarea,select,[contenteditable]')) {
              const text = (element.innerText || '').replace(/\\s+/g, ' ').trim();
              if (text && text.length <= 160) {
                semantic.push(tag + ':text-is(' + JSON.stringify(text) + ')');
                semantic.push(tag + ':has-text(' + JSON.stringify(text) + ')');
              }
            }
            for (const name of ['name', 'placeholder', 'title', 'alt', 'href']) {
              const value = element.getAttribute(name);
              if (value && value.length <= 300) semantic.push(tag + attribute(name, value));
            }
            for (const candidate of semantic) candidates.add(candidate);
            // Disambiguate repeated labels using nearby stable ancestors before using positions.
            let ancestor = element.parentElement;
            let depth = 0;
            while (ancestor && ancestor !== document.body && depth++ < 8) {
              const scopes = anchors(ancestor);
              if (ancestor.matches('header,main,nav,section,article,aside,footer,form')) scopes.push(ancestor.tagName.toLowerCase());
              for (const scope of scopes) {
                for (const candidate of semantic) candidates.add(scope + ' ' + candidate);
                candidates.add(scope + ' ' + tag);
              }
              ancestor = ancestor.parentElement;
            }
            // Stop the structural fallback as soon as it uniquely identifies this exact node.
            const unique = selector => {
              try { const matches = document.querySelectorAll(selector); return matches.length === 1 && matches[0] === element; }
              catch { return false; }
            };
            const segments = [];
            let current = element;
            while (current && current !== document.body) {
              const currentTag = current.tagName.toLowerCase();
              const suffix = segments.length ? ' > ' + segments.join(' > ') : '';
              for (const anchor of [...anchors(current), currentTag]) {
                const candidate = anchor + suffix;
                if (unique(candidate)) {
                  candidates.add(candidate);
                  return [...candidates];
                }
              }
              let selector = currentTag;
              const siblings = current.parentElement ? [...current.parentElement.children].filter(e => e.tagName === current.tagName) : [];
              if (siblings.length > 1) selector += ':nth-of-type(' + (siblings.indexOf(current) + 1) + ')';
              segments.unshift(selector);
              if (unique(segments.join(' > '))) {
                candidates.add(segments.join(' > '));
                return [...candidates];
              }
              current = current.parentElement;
            }
            const fallback = 'body > ' + segments.join(' > ');
            if (segments.length && unique(fallback)) candidates.add(fallback);
            return [...candidates];
            }
            """;

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
                  return {element};
                }
              };
              const capture = window.CAPTURE_BINDING;
              // Request frames while Playwright waits for page events; Java throttles capture work.
              // Ignore callback rejections when navigation or session shutdown removes the binding.
              setInterval(() => { capture().catch(() => {}); }, 200);
            })();
            """;
}
