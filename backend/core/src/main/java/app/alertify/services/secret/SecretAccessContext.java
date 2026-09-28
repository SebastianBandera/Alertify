package app.alertify.services.secret;

import java.util.Map;
import java.util.Objects;

/** Identifies the application resource that directly consumes a secret value. */
public record SecretAccessContext(ConsumerType consumerType, long consumerId, String consumerName) {

    public SecretAccessContext {
        Objects.requireNonNull(consumerType, "consumerType must not be null");
        Objects.requireNonNull(consumerName, "consumerName must not be null");
    }

    public static SecretAccessContext alert(long id, String name) {
        return new SecretAccessContext(ConsumerType.ALERT, id, name);
    }

    public static SecretAccessContext procedure(long id, String name) {
        return new SecretAccessContext(ConsumerType.PROCEDURE, id, name);
    }

    public static SecretAccessContext hook(long id, String name) {
        return new SecretAccessContext(ConsumerType.HOOK, id, name);
    }

    public void addTo(Map<String, Object> data) {
        data.put("consumerType", consumerType.name());
        data.put("consumerId", consumerId);
        data.put("consumerName", consumerName);
    }

    public enum ConsumerType {
        ALERT,
        PROCEDURE,
        HOOK
    }
}
