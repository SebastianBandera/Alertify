package app.alertify.worker.codex;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

@Component
class EncryptedStateStore {

    private static final byte FORMAT_VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path stateFile;
    private final SecretKey key;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
    private final SecureRandom secureRandom = new SecureRandom();

    EncryptedStateStore(CodexWorkerProperties properties) {
        stateFile = properties.stateFile();
        byte[] decoded;
        try {
            try {
                decoded = Base64.getDecoder().decode(properties.stateKey());
            } catch (IllegalArgumentException exception) {
                decoded = Base64.getUrlDecoder().decode(properties.stateKey());
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("AI_WORKER_STATE_KEY must be a Base64 encoded 256-bit key", exception);
        }
        if (decoded.length != 32)
            throw new IllegalStateException("AI_WORKER_STATE_KEY must decode to exactly 32 bytes");

        key = new SecretKeySpec(decoded, "AES");
    }

    synchronized PersistentAiState load() {
        if (!Files.exists(stateFile))
            return null;

        try {
            byte[] encrypted = Files.readAllBytes(stateFile);
            if (encrypted.length <= 1 + NONCE_BYTES || encrypted[0] != FORMAT_VERSION)
                throw new IllegalStateException("The encrypted AI state has an unsupported format");

            byte[] nonce = new byte[NONCE_BYTES];
            ByteBuffer.wrap(encrypted, 1, NONCE_BYTES).get(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return jsonMapper.readValue(cipher.doFinal(encrypted, 1 + NONCE_BYTES, encrypted.length - 1 - NONCE_BYTES), PersistentAiState.class);
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("The encrypted AI state could not be read", exception);
        }
    }

    synchronized void save(PersistentAiState state) {
        try {
            Files.createDirectories(stateFile.toAbsolutePath().getParent());
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] payload = cipher.doFinal(jsonMapper.writeValueAsBytes(state));
            ByteBuffer output = ByteBuffer.allocate(1 + nonce.length + payload.length);
            output.put(FORMAT_VERSION).put(nonce).put(payload);

            Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.write(temporary, output.array());
            setOwnerOnly(temporary);
            try {
                Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
            setOwnerOnly(stateFile);
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("The encrypted AI state could not be saved", exception);
        }
    }

    private static void setOwnerOnly(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        } catch (UnsupportedOperationException ignored) {
            // The production worker filesystem is POSIX. Windows development filesystems use their inherited ACL.
        }
    }
}
