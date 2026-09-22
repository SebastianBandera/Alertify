package app.alertify.procedures.templates;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.procedures.ProcedureEvaluator;
import app.alertify.procedures.ProcedureExecutionContext;
import app.alertify.procedures.artifact.ProcedureArtifactInput;
import app.alertify.procedures.template.annotation.ProcedureParameter;
import app.alertify.procedures.template.annotation.ProcedureTemplate;
import app.alertify.procedures.template.annotation.ProcedureTemplateTag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ProcedureTemplate(
    nameKey = "procedures.template.copyFileToNfs.name",
    descriptionKey = "procedures.template.copyFileToNfs.description",
    tags = @ProcedureTemplateTag(nameKey = "procedures.templateTag.network", color = "#0EA5E9"),
    sourcePath = "app/alertify/procedures/templates/CopyFileToNfsProcedureTemplate.java"
)
public final class CopyFileToNfsProcedureTemplate implements ProcedureEvaluator {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path HELPER_SOCKET = Path.of("/run/alertify-nfs-helper/helper.sock");
    private static final Path MOUNT_ROOT = Path.of("/run/alertify-nfs-mounts");
    private static final Pattern SERVER = Pattern.compile("^[A-Za-z0-9._\\-:\\[\\]]{1,255}$");

    @ProcedureParameter(
        labelKey = "procedures.template.copyFileToNfs.input",
        descriptionKey = "procedures.template.copyFileToNfs.inputDescription",
        allowedSources = { AlertParameterSource.PIPE_OUTPUT, AlertParameterSource.CONFIGURATION, AlertParameterSource.SECRET },
        allowedConfigurationValueTypes = "BINARY",
        allowedSecretValueTypes = "BINARY",
        required = false,
        order = 1
    )
    private final ProcedureArtifactInput input;

    @ProcedureParameter(
        labelKey = "procedures.template.copyFileToNfs.server",
        descriptionKey = "procedures.template.copyFileToNfs.serverDescription",
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION },
        allowedConfigurationValueTypes = { "STRING", "EXPRESSION" },
        order = 2
    )
    private final String server;

    @ProcedureParameter(
        labelKey = "procedures.template.copyFileToNfs.export",
        descriptionKey = "procedures.template.copyFileToNfs.exportDescription",
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION },
        allowedConfigurationValueTypes = { "STRING", "EXPRESSION" },
        order = 3
    )
    private final String export;

    @ProcedureParameter(
        labelKey = "procedures.template.copyFileToNfs.version",
        descriptionKey = "procedures.template.copyFileToNfs.versionDescription",
        options = { "3", "4" },
        bindingAllowed = false,
        defaultValue = "4",
        order = 4
    )
    private final String version;

    @ProcedureParameter(
        labelKey = "procedures.template.copyFileToNfs.directory",
        descriptionKey = "procedures.template.copyFileToNfs.directoryDescription",
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION },
        allowedConfigurationValueTypes = { "STRING", "EXPRESSION" },
        required = false,
        order = 5
    )
    private final String directory;

    @ProcedureParameter(
        labelKey = "procedures.template.copyFileToNfs.fileName",
        descriptionKey = "procedures.template.copyFileToNfs.fileNameDescription",
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION },
        allowedConfigurationValueTypes = { "STRING", "EXPRESSION" },
        required = false,
        order = 6
    )
    private final String fileName;

    @ProcedureParameter(
        labelKey = "procedures.template.copyFileToNfs.overwriteExisting",
        descriptionKey = "procedures.template.copyFileToNfs.overwriteExistingDescription",
        options = { "true", "false" },
        bindingAllowed = false,
        defaultValue = "false",
        order = 7
    )
    private final boolean overwriteExisting;

    public CopyFileToNfsProcedureTemplate(ProcedureArtifactInput input, String server, String export, String version, String directory, String fileName, boolean overwriteExisting) {
        this.input = input;
        this.server = server;
        this.export = export;
        this.version = version;
        this.directory = directory;
        this.fileName = fileName;
        this.overwriteExisting = overwriteExisting;
    }

    @Override
    public JsonNode execute(ProcedureExecutionContext context) throws Exception {
        if (input == null)
            throw new IllegalArgumentException("MISSING_ARTIFACT_INPUT: input requires a Pipe output or BINARY fallback");
        validateServer(server);
        validateExport(export);
        if (!List.of("3", "4").contains(version))
            throw new IllegalArgumentException("NFS version must be 3 or 4");

        String targetName = logicalFileName(fileName == null || fileName.isBlank() ? input.fileName() : fileName.trim());
        Path relativeDirectory = relativeDirectory(directory);
        Mount mount = Helper.mount(server, export, version);
        Exception failure = null;
        CopyResult result = null;
        try {
            result = copy(mount.path(), relativeDirectory, targetName);
        } catch (Exception exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                Helper.unmount(mount.token());
            } catch (Exception unmountFailure) {
                if (failure != null)
                    failure.addSuppressed(unmountFailure);
                else
                    throw unmountFailure;
            }
        }

        return JSON.createObjectNode().put("fileName", targetName)
                .put("relativeDirectory", relativeDirectory.toString().replace('\\', '/'))
                .put("size", result.size()).put("sha256", result.sha256()).put("nfsVersion", version)
                .put("overwritten", result.overwritten());
    }

    private CopyResult copy(Path mountPath, Path relativeDirectory, String targetName) throws Exception {
        Path normalizedRoot = mountPath.toAbsolutePath().normalize();
        if (!normalizedRoot.startsWith(MOUNT_ROOT) || normalizedRoot.equals(MOUNT_ROOT) || Files.isSymbolicLink(normalizedRoot))
            throw new IllegalStateException("NFS helper returned an invalid mount location");

        Path parent = normalizedRoot;
        for (Path element : relativeDirectory) {
            parent = parent.resolve(element.toString());
            if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
                    throw new IllegalArgumentException("NFS destination contains a symlink or non-directory component");
            } else {
                Files.createDirectory(parent);
            }
        }

        Path target = parent.resolve(targetName);
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (exists && Files.isSymbolicLink(target))
            throw new IllegalArgumentException("NFS destination file must not be a symlink");
        if (exists && !overwriteExisting)
            throw new IllegalStateException("NFS destination already exists and overwriteExisting is false");

        Path temporary = Files.createTempFile(parent, ".alertify-", ".partial");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try (InputStream source = input.openStream();
                    OutputStream destination = Files.newOutputStream(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = source.read(buffer)) >= 0) {
                    if (read == 0)
                        continue;

                    destination.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    count += read;
                }
            }
            byte[] sha256 = digest.digest();
            if (count != input.size() || !Arrays.equals(sha256, input.sha256()))
                throw new IllegalStateException("Copied artifact size or SHA-256 does not match its descriptor");

            try {
                if (overwriteExisting)
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                else
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IllegalStateException("NFS destination does not support atomic rename", exception);
            }
            if (Files.size(target) != count)
                throw new IllegalStateException("NFS destination size changed after atomic rename");

            return new CopyResult(count, HexFormat.of().formatHex(sha256), exists);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path relativeDirectory(String value) {
        if (value == null || value.isBlank())
            return Path.of("");
        Path result = Path.of(value.trim()).normalize();
        if (result.isAbsolute() || result.startsWith("..") || value.contains("\\") || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("directory must be a relative NFS path without '..'");
        for (Path element : result) {
            String name = element.toString();
            if (name.equals(".") || name.equals("..") || name.isBlank())
                throw new IllegalArgumentException("directory contains an invalid path component");
        }
        return result;
    }

    private static String logicalFileName(String value) {
        if (value == null || value.isBlank() || value.length() > 255 || value.equals(".") || value.equals("..")
                || value.contains("/") || value.contains("\\") || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("fileName must be a logical file name without path components");

        return value;
    }

    private static void validateServer(String value) {
        if (value == null || !SERVER.matcher(value.trim()).matches())
            throw new IllegalArgumentException("server contains unsupported characters");
    }

    private static void validateExport(String value) {
        if (value == null || !value.startsWith("/") || value.length() > 1024 || value.contains("..")
                || value.contains(",") || value.chars().anyMatch(character -> Character.isWhitespace(character) || Character.isISOControl(character)))
            throw new IllegalArgumentException("export must be an absolute NFS export without '..' or mount-option characters");
    }

    private static final class Helper {
        private static Mount mount(String server, String export, String version) throws IOException {
            try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel.connect(UnixDomainSocketAddress.of(HELPER_SOCKET));
                DataOutputStream output = new DataOutputStream(Channels.newOutputStream(channel));
                output.writeByte(1);
                output.writeUTF(server.trim());
                output.writeUTF(export);
                output.writeUTF(version);
                output.flush();
                DataInputStream input = new DataInputStream(Channels.newInputStream(channel));
                if (!input.readBoolean())
                    throw new IllegalStateException("NFS mount helper rejected the mount: " + input.readUTF());

                return new Mount(input.readUTF(), Path.of(input.readUTF()));
            }
        }

        private static void unmount(String token) throws IOException {
            try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel.connect(UnixDomainSocketAddress.of(HELPER_SOCKET));
                DataOutputStream output = new DataOutputStream(Channels.newOutputStream(channel));
                output.writeByte(2);
                output.writeUTF(token);
                output.flush();
                DataInputStream input = new DataInputStream(Channels.newInputStream(channel));
                if (!input.readBoolean())
                    throw new IllegalStateException("NFS unmount failed; deferred cleanup was scheduled: " + input.readUTF());
            }
        }
    }

    private record Mount(String token, Path path) { }
    private record CopyResult(long size, String sha256, boolean overwritten) { }
}
