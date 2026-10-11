package app.alertify.logging;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Safe request metadata retained across an HTTP-to-WebSocket upgrade. */
public record RequestLogContext(UUID requestId, String path, String method, long startedNanos) {

    public static final String REQUEST_ATTRIBUTE = RequestLogContext.class.getName();
    public static final String DEFERRED_ATTRIBUTE = RequestLogContext.class.getName() + ".deferred";

    public Map<String, Object> data(int status) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("method", method);
        data.put("path", path);
        data.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        return data;
    }
}
