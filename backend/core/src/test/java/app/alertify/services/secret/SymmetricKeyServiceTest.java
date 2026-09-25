package app.alertify.services.secret;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.junit.jupiter.api.Test;

class SymmetricKeyServiceTest {

    @Test
    void derivesAes256KeyWithPrivateClassPartBetweenDatabaseAndEnvironmentParts() throws Exception {
        byte[] databaseKeyPart = "database-key-part".getBytes(StandardCharsets.UTF_8);
        byte[] privateClassKeyPart = "private-class-key-part".getBytes(StandardCharsets.UTF_8);
        byte[] environmentKeyPart = "environment-key-part".getBytes(StandardCharsets.UTF_8);
        DatabaseKeyPartSource databaseSource = mock(DatabaseKeyPartSource.class);
        PrivateClassKeyPartSource privateClassSource = mock(PrivateClassKeyPartSource.class);
        when(databaseSource.read()).thenReturn("database-key-part");
        when(privateClassSource.read()).thenReturn("private-class-key-part");
        SymmetricKeyService service = new SymmetricKeyService(
            databaseSource,
            privateClassSource,
            new EnvironmentKeyPartSource("environment-key-part"),
            new Sha256HashService()
        );

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(databaseKeyPart.length).array());
        digest.update(databaseKeyPart);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(privateClassKeyPart.length).array());
        digest.update(privateClassKeyPart);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(environmentKeyPart.length).array());
        digest.update(environmentKeyPart);

        try (SymmetricKeyRotation rotation = service.readRotation()) {
            service.activate(rotation.newKey());
        }

        assertEquals("AES", service.getKey().getAlgorithm());
        assertEquals(32, service.getKey().getEncoded().length);
        assertArrayEquals(digest.digest(), service.getKey().getEncoded());
    }


    @Test
    void preservesPreviousDerivationWhenPrivateClassPartIsEmpty() throws Exception {
        byte[] databaseKeyPart = "database-key-part".getBytes(StandardCharsets.UTF_8);
        byte[] environmentKeyPart = "environment-key-part".getBytes(StandardCharsets.UTF_8);
        DatabaseKeyPartSource databaseSource = mock(DatabaseKeyPartSource.class);
        PrivateClassKeyPartSource privateClassSource = mock(PrivateClassKeyPartSource.class);
        when(databaseSource.read()).thenReturn("database-key-part");
        when(privateClassSource.read()).thenReturn("");
        SymmetricKeyService service = new SymmetricKeyService(
            databaseSource,
            privateClassSource,
            new EnvironmentKeyPartSource("environment-key-part"),
            new Sha256HashService()
        );

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(databaseKeyPart.length).array());
        digest.update(databaseKeyPart);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(environmentKeyPart.length).array());
        digest.update(environmentKeyPart);

        try (SymmetricKeyRotation rotation = service.readRotation()) {
            service.activate(rotation.newKey());
        }

        assertArrayEquals(digest.digest(), service.getKey().getEncoded());
    }

    @Test
    void rejectsBlankEnvironmentPart() {
        assertThrows(IllegalStateException.class, () -> new EnvironmentKeyPartSource(" "));
    }

    @Test
    void parsesTransitionsIndependentlyAndActivatesTheNewKey() throws Exception {
        DatabaseKeyPartSource databaseSource = mock(DatabaseKeyPartSource.class);
        PrivateClassKeyPartSource privateClassSource = mock(PrivateClassKeyPartSource.class);
        when(databaseSource.read()).thenReturn("database-old->database-new");
        when(privateClassSource.read()).thenReturn("->private-new");
        SymmetricKeyService service = new SymmetricKeyService(
                databaseSource, privateClassSource, new EnvironmentKeyPartSource("environment"), new Sha256HashService()
        );

        try (SymmetricKeyRotation rotation = service.readRotation()) {
            assertEquals(true, rotation.transition());
            service.activate(rotation.newKey());
        }

        byte[] database = "database-new".getBytes(StandardCharsets.UTF_8);
        byte[] privatePart = "private-new".getBytes(StandardCharsets.UTF_8);
        byte[] environment = "environment".getBytes(StandardCharsets.UTF_8);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(database.length).array());
        digest.update(database);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(privatePart.length).array());
        digest.update(privatePart);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(environment.length).array());
        digest.update(environment);
        assertArrayEquals(digest.digest(), service.getKey().getEncoded());
    }

    @Test
    void rejectsEmptyRequiredTransitionSide() {
        DatabaseKeyPartSource databaseSource = mock(DatabaseKeyPartSource.class);
        PrivateClassKeyPartSource privateClassSource = mock(PrivateClassKeyPartSource.class);
        when(databaseSource.read()).thenReturn("old->");
        when(privateClassSource.read()).thenReturn("");
        SymmetricKeyService service = new SymmetricKeyService(
                databaseSource, privateClassSource, new EnvironmentKeyPartSource("environment"), new Sha256HashService()
        );

        assertThrows(IllegalStateException.class, service::readRotation);
    }
}
