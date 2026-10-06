package app.alertify.ai;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Trusted identity and correlation data for one AI tool invocation. */
public record AiInvocationContext(
    String userSubject,
    String username,
    Set<String> authorities,
    Long conversationId,
    UUID invocationId
) {
    public AiInvocationContext {
        Objects.requireNonNull(userSubject);
        Objects.requireNonNull(username);
        if (userSubject.isBlank() || username.isBlank())
            throw new IllegalArgumentException("AI user identity must not be blank");

        authorities = Set.copyOf(authorities);
        Objects.requireNonNull(invocationId);
        new AiProvenance(true, conversationId);
    }

    public AiProvenance provenance() {
        return new AiProvenance(true, conversationId);
    }
}
