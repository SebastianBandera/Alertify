package app.alertify.services.secret;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.stereotype.Service;

@Service
public class Sha256HashService {

    private static final String HASH_ALGORITHM = "SHA-256";

    public byte[] hash(String value) {
        if (value == null) {
            throw new IllegalArgumentException("The value to hash must not be null");
        }

        return hash(value.getBytes(StandardCharsets.UTF_8));
    }

    byte[] hash(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("The value to hash must not be null");
        }

        try {
            return MessageDigest.getInstance(HASH_ALGORITHM).digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
