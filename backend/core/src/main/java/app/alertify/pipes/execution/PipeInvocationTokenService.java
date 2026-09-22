package app.alertify.pipes.execution;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import app.alertify.pipes.PipeExecutionException;
import app.alertify.procedures.ProcedureDepthExceededException;
import app.alertify.procedures.execution.ProcedureExecutionProperties;
import app.alertify.procedures.execution.ProcedureInvocationRegistry;

@Service
public class PipeInvocationTokenService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final byte[] key = new byte[32];
    private final ProcedureInvocationRegistry registry;
    private final ProcedureExecutionProperties properties;

    public PipeInvocationTokenService(ProcedureInvocationRegistry registry, ProcedureExecutionProperties properties) {
        this.registry = registry;
        this.properties = properties;
        new SecureRandom().nextBytes(key);
    }

    public String issue(long pipeId, UUID rootExecutionId, UUID parentProcedureExecutionId, int depth, Instant deadline) {
        String payload = String.join("|", "1", Long.toString(pipeId), rootExecutionId.toString(),
                parentProcedureExecutionId.toString(), Integer.toString(depth), Long.toString(deadline.toEpochMilli()));
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(bytes) + "." + ENCODER.encodeToString(sign(bytes));
    }

    public Claims validate(String token) {
        try {
            String[] encoded = token == null ? new String[0] : token.split("\\.", -1);
            if (encoded.length != 2)
                throw invalid();

            byte[] payload = DECODER.decode(encoded[0]);
            if (!MessageDigest.isEqual(DECODER.decode(encoded[1]), sign(payload)))
                throw invalid();

            String[] values = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
            if (values.length != 6 || !"1".equals(values[0]))
                throw invalid();

            Claims claims = new Claims(Long.parseLong(values[1]), UUID.fromString(values[2]), UUID.fromString(values[3]),
                    Integer.parseInt(values[4]), Instant.ofEpochMilli(Long.parseLong(values[5])));
            Instant now = Instant.now();
            if (!claims.deadline().isAfter(now))
                throw new PipeExecutionException("Pipe invocation deadline has expired");
            if (claims.depth() > properties.maxDepth())
                throw new ProcedureDepthExceededException("Procedure-Pipe invocation depth exceeds " + properties.maxDepth());
            if (!registry.isActive(claims.parentProcedureExecutionId(), now))
                throw new PipeExecutionException("Pipe invocation parent is no longer active");

            return claims;
        } catch (PipeExecutionException | ProcedureDepthExceededException exception) {
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

    private static PipeExecutionException invalid() { return new PipeExecutionException("Pipe invocation token is invalid"); }

    public record Claims(long pipeId, UUID rootExecutionId, UUID parentProcedureExecutionId, int depth, Instant deadline) {
    }
}
