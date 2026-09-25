package app.alertify.services.secret;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import app.alertify.jpa.repository.ApplicationSecretRepository;

/** Classifies and resumably rotates every encrypted secret before normal startup. */
@Service
public class SecretKeyRotationStartupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecretKeyRotationStartupService.class);
    private static final long ADVISORY_LOCK_ID = 0x414c4552544b4559L;
    private static final String SECRET_QUERY = "select id, encrypted_value, encryption_iv, value_hash, hash_salt, encryption_version from secrets.secrets order by id";
    private static final String BINARY_QUERY = "select secret_id, encrypted_value, encryption_iv, value_hash, hash_salt, encryption_version from secrets.secret_binary_values order by secret_id";
    private static final String MISSING_BINARY_QUERY = "select s.id from secrets.secrets s left join secrets.secret_binary_values b on b.secret_id = s.id where s.value_type = 'BINARY' and b.secret_id is null order by s.id";

    private final DataSource dataSource;
    private final SymmetricKeyService symmetricKeyService;
    private final SecretEncryptionService encryptionService;
    private final SecretKeyRotationTransactionService transactionService;
    private final ApplicationSecretRepository secretRepository;

    public SecretKeyRotationStartupService(DataSource dataSource, SymmetricKeyService symmetricKeyService, SecretEncryptionService encryptionService, SecretKeyRotationTransactionService transactionService, ApplicationSecretRepository secretRepository) {
        this.dataSource = dataSource;
        this.symmetricKeyService = symmetricKeyService;
        this.encryptionService = encryptionService;
        this.transactionService = transactionService;
        this.secretRepository = secretRepository;
    }

    public void initializeAndRotateIfRequired() {
        try (SymmetricKeyRotation initial = symmetricKeyService.readRotation()) {
            if (!initial.transition()) {
                symmetricKeyService.activate(initial.newKey());
                return;
            }
        }

        try (Connection lockConnection = dataSource.getConnection()) {
            acquireLock(lockConnection);
            lockConnection.setAutoCommit(false);
            try (SymmetricKeyRotation rotation = symmetricKeyService.readRotation()) {
                if (!rotation.transition()) {
                    symmetricKeyService.activate(rotation.newKey());
                    return;
                }

                LOGGER.info("Secret-key transition detected; classifying encrypted values before writing");
                Classification classification = classify(lockConnection, rotation.oldKey(), rotation.newKey());
                lockConnection.commit();
                if (!classification.unrecoverable().isEmpty())
                    throw new IllegalStateException("Secret-key rotation found unrecoverable encrypted values: " + String.join(", ", classification.unrecoverable()));

                LOGGER.info("Secret-key classification completed: old={}, new={}", classification.oldCount(), classification.newCount());
                for (Long secretId : secretRepository.findAllIds())
                    transactionService.migrate(secretId, rotation.oldKey(), rotation.newKey());

                List<String> verificationFailures = verifyWithNewKey(lockConnection, rotation.newKey());
                lockConnection.commit();
                if (!verificationFailures.isEmpty())
                    throw new IllegalStateException("Secret-key rotation verification failed for: " + String.join(", ", verificationFailures));

                symmetricKeyService.activate(rotation.newKey());
                LOGGER.info("Secret-key rotation completed and the new key is active");
            } finally {
                rollbackQuietly(lockConnection);
                releaseLock(lockConnection);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Secret-key rotation could not coordinate through PostgreSQL", exception);
        }
    }

    private Classification classify(Connection connection, ObfuscatedKeyMaterial oldKey, ObfuscatedKeyMaterial newKey) throws SQLException {
        List<String> unrecoverable = new ArrayList<>();
        long[] counts = new long[2];
        scan(connection, SECRET_QUERY, "secret", oldKey, newKey, counts, unrecoverable);
        scan(connection, BINARY_QUERY, "binary secret", oldKey, newKey, counts, unrecoverable);
        findMissingBinaryValues(connection, unrecoverable);
        return new Classification(counts[0], counts[1], List.copyOf(unrecoverable));
    }

    private List<String> verifyWithNewKey(Connection connection, ObfuscatedKeyMaterial newKey) throws SQLException {
        List<String> failures = new ArrayList<>();
        verify(connection, SECRET_QUERY, "secret", newKey, failures);
        verify(connection, BINARY_QUERY, "binary secret", newKey, failures);
        findMissingBinaryValues(connection, failures);
        return failures;
    }

    private void scan(Connection connection, String sql, String kind, ObfuscatedKeyMaterial oldKey, ObfuscatedKeyMaterial newKey, long[] counts, List<String> unrecoverable) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setFetchSize(20);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String identifier = kind + " " + result.getLong(1);
                    if (isRecoverable(result, identifier, oldKey)) {
                        counts[0]++;
                    } else if (isRecoverable(result, identifier, newKey)) {
                        counts[1]++;
                    } else {
                        unrecoverable.add(identifier);
                    }
                }
            }
        }
    }

    private void verify(Connection connection, String sql, String kind, ObfuscatedKeyMaterial newKey, List<String> failures) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setFetchSize(20);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String identifier = kind + " " + result.getLong(1);
                    if (!isRecoverable(result, identifier, newKey))
                        failures.add(identifier);
                }
            }
        }
    }

    private boolean isRecoverable(ResultSet result, String identifier, ObfuscatedKeyMaterial key) throws SQLException {
        byte[] plaintext = null;
        try {
            plaintext = encryptionService.decryptAndVerify(
                    result.getBytes(2), result.getBytes(3), result.getBytes(4), result.getBytes(5),
                    result.getShort(6), identifier, key
            );
            return true;
        } catch (SecretNotRecoverableException exception) {
            return false;
        } finally {
            if (plaintext != null)
                Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static void findMissingBinaryValues(Connection connection, List<String> failures) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(MISSING_BINARY_QUERY)) {
            while (result.next())
                failures.add("missing binary secret " + result.getLong(1));
        }
    }

    private static void acquireLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_lock(?)")) {
            statement.setLong(1, ADVISORY_LOCK_ID);
            statement.execute();
        }
    }

    private static void releaseLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
            statement.setLong(1, ADVISORY_LOCK_ID);
            statement.execute();
        } catch (SQLException exception) {
            LOGGER.warn("PostgreSQL advisory lock could not be explicitly released; closing its connection", exception);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException exception) {
            LOGGER.warn("PostgreSQL key-rotation transaction could not be rolled back before releasing its lock", exception);
        }
    }

    private record Classification(long oldCount, long newCount, List<String> unrecoverable) { }
}
