package app.alertify.procedures.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import app.alertify.procedures.ProcedureExecutionContext;
import app.alertify.procedures.artifact.ProcedureArtifactInput;
import tools.jackson.databind.JsonNode;

class CopyFileToNfsProcedureTemplateTest {
    private static final byte[] CONTENT = "backup content".getBytes(StandardCharsets.UTF_8);
    private static final String MOUNT_TOKEN = "test-mount-token";

    @Test
    void unmountsAfterSuccessfulCopy() throws Exception {
        ByteArrayOutputStream unmountRequest = new ByteArrayOutputStream();

        JsonNode result = executeCopy(null, true, unmountRequest);

        assertThat(result.get("fileName").asText()).isEqualTo("backup.bak");
        assertThat(result.get("size").asLong()).isEqualTo(CONTENT.length);
        assertUnmountRequested(unmountRequest);
    }

    @Test
    void reportsUnmountFailureAfterSuccessfulCopy() {
        ByteArrayOutputStream unmountRequest = new ByteArrayOutputStream();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> executeCopy(null, false, unmountRequest));

        assertThat(failure).hasMessageContaining("NFS unmount failed");
        assertUnmountRequested(unmountRequest);
    }

    @Test
    void preservesCopyExceptionAndSuppressesUnmountFailure() {
        ByteArrayOutputStream unmountRequest = new ByteArrayOutputStream();
        IOException copyFailure = new IOException("Artifact read failed");

        IOException failure = assertThrows(IOException.class,
                () -> executeCopy(copyFailure, false, unmountRequest));

        assertThat(failure).isSameAs(copyFailure);
        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(failure.getSuppressed()[0]).isInstanceOf(IllegalStateException.class).hasMessageContaining("NFS unmount failed");
        assertUnmountRequested(unmountRequest);
    }

    @Test
    void preservesCopyErrorAndSuppressesUnmountFailure() {
        ByteArrayOutputStream unmountRequest = new ByteArrayOutputStream();
        AssertionError copyFailure = new AssertionError("Artifact stream failed");

        AssertionError failure = assertThrows(AssertionError.class,
                () -> executeCopy(copyFailure, false, unmountRequest));

        assertThat(failure).isSameAs(copyFailure);
        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(failure.getSuppressed()[0]).isInstanceOf(IllegalStateException.class).hasMessageContaining("NFS unmount failed");
        assertUnmountRequested(unmountRequest);
    }

    private static JsonNode executeCopy(Throwable copyFailure, boolean unmountSucceeds, ByteArrayOutputStream unmountRequest) throws Exception {
        Path mountPath = Path.of("/run/alertify-nfs-mounts/test-mount");
        Path parent = mountPath.resolve("exports");
        Path temporary = parent.resolve("test.partial");
        SocketChannel mountChannel = mock(SocketChannel.class);
        SocketChannel unmountChannel = mock(SocketChannel.class);
        ByteArrayOutputStream mountReply = new ByteArrayOutputStream();
        try (DataOutputStream reply = new DataOutputStream(mountReply)) {
            reply.writeBoolean(true);
            reply.writeUTF(MOUNT_TOKEN);
            reply.writeUTF(mountPath.toString());
        }
        ByteArrayOutputStream unmountReply = new ByteArrayOutputStream();
        try (DataOutputStream reply = new DataOutputStream(unmountReply)) {
            reply.writeBoolean(unmountSucceeds);
            if (!unmountSucceeds)
                reply.writeUTF("Unmount rejected");
        }
        ProcedureArtifactInput input = new ProcedureArtifactInput("backup.bak", "application/octet-stream", CONTENT.length,
                MessageDigest.getInstance("SHA-256").digest(CONTENT), () -> {
                    if (copyFailure instanceof IOException exception)
                        throw exception;

                    if (copyFailure instanceof Error error)
                        throw error;

                    return new ByteArrayInputStream(CONTENT);
                });

        try (var sockets = mockStatic(SocketChannel.class);
                var channels = mockStatic(Channels.class);
                var files = mockStatic(Files.class)) {
            sockets.when(() -> SocketChannel.open(StandardProtocolFamily.UNIX)).thenReturn(mountChannel, unmountChannel);
            channels.when(() -> Channels.newInputStream(mountChannel)).thenReturn(new ByteArrayInputStream(mountReply.toByteArray()));
            channels.when(() -> Channels.newOutputStream(mountChannel)).thenReturn(new ByteArrayOutputStream());
            channels.when(() -> Channels.newInputStream(unmountChannel)).thenReturn(new ByteArrayInputStream(unmountReply.toByteArray()));
            channels.when(() -> Channels.newOutputStream(unmountChannel)).thenReturn(unmountRequest);
            files.when(() -> Files.createTempFile(parent, ".alertify-", ".partial")).thenReturn(temporary);
            files.when(() -> Files.newOutputStream(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))
                    .thenReturn(new ByteArrayOutputStream());
            files.when(() -> Files.size(parent.resolve("backup.bak"))).thenReturn((long) CONTENT.length);

            return new CopyFileToNfsProcedureTemplate(input, "nfs.example", "/backups", "4", "exports", null, false)
                    .execute(new ProcedureExecutionContext(Instant.EPOCH, Map.of()));
        }
    }

    private static void assertUnmountRequested(ByteArrayOutputStream request) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(request.toByteArray()))) {
            assertThat(input.readByte()).isEqualTo((byte) 2);
            assertThat(input.readUTF()).isEqualTo(MOUNT_TOKEN);
            assertThat(input.available()).isZero();
        } catch (IOException exception) {
            throw new AssertionError("Missing or invalid unmount request", exception);
        }
    }
}
