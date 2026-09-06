package app.alertify.procedures.execution;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import app.alertify.procedures.ProcedureDepthExceededException;
import app.alertify.procedures.ProcedureExecutionException;
import app.alertify.worker.grpc.ProcedureParentKind;

/** Issues short-lived, parent-bound opaque capabilities for procedure handles. */
@Component
public class ProcedureInvocationTokenService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final byte[] key = new byte[32];
    private final ProcedureInvocationRegistry registry;
    private final ProcedureExecutionProperties properties;

    public ProcedureInvocationTokenService(ProcedureInvocationRegistry registry, ProcedureExecutionProperties properties) {
        this.registry = registry;
        this.properties = properties;
        new SecureRandom().nextBytes(key);
    }

    public String issue(long procedureId, UUID rootExecutionId, UUID parentExecutionId, ProcedureParentKind parentKind, int depth, Instant deadline) {
        String payload = String.join("|", "1", Long.toString(procedureId), rootExecutionId.toString(),
                parentExecutionId.toString(), parentKind.name(), Integer.toString(depth),
                Long.toString(deadline.toEpochMilli()));
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(bytes) + "." + ENCODER.encodeToString(sign(bytes));
    }

    public Claims validate(String token) {
        try {
            String[] encoded = token == null ? new String[0] : token.split("\\.", -1);
            if (encoded.length != 2)
                throw invalid();

            byte[] payloadBytes = DECODER.decode(encoded[0]);
            byte[] signature = DECODER.decode(encoded[1]);
            if (!MessageDigest.isEqual(signature, sign(payloadBytes)))
                throw invalid();

            String[] values = new String(payloadBytes, StandardCharsets.UTF_8).split("\\|", -1);
            if (values.length != 7 || !"1".equals(values[0]))
                throw invalid();

            Claims claims = new Claims(Long.parseLong(values[1]), UUID.fromString(values[2]),
                    UUID.fromString(values[3]), ProcedureParentKind.valueOf(values[4]),
                    Integer.parseInt(values[5]), Instant.ofEpochMilli(Long.parseLong(values[6])));
            Instant now = Instant.now();
            if (!claims.deadline().isAfter(now))
                throw new ProcedureExecutionException("Procedure invocation deadline has expired");

            if (claims.depth() > properties.maxDepth())
                throw new ProcedureDepthExceededException("Procedure invocation depth exceeds " + properties.maxDepth());

            if (!registry.isActive(claims.parentExecutionId(), now))
                throw new ProcedureExecutionException("Procedure invocation parent is no longer active");

            return claims;
        } catch (ProcedureExecutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static ProcedureExecutionException invalid() {
        return new ProcedureExecutionException("Procedure invocation token is invalid");
    }

    public record Claims(long procedureId, UUID rootExecutionId, UUID parentExecutionId,
            ProcedureParentKind parentKind, int depth, Instant deadline) {
    }
}
