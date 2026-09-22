package app.alertify.worker.standard;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Root-only, local Unix-socket helper that performs tightly scoped NFS mounts. */
public final class NfsMountHelperMain {
    private static final Path SOCKET_DIRECTORY = Path.of("/run/alertify-nfs-helper");
    private static final Path SOCKET = SOCKET_DIRECTORY.resolve("helper.sock");
    private static final Path MOUNT_ROOT = Path.of("/run/alertify-nfs-mounts");
    private static final Pattern SERVER = Pattern.compile("^[A-Za-z0-9._\\-:\\[\\]]{1,255}$");
    private static final Pattern TOKEN = Pattern.compile("^[0-9a-f]{32}$");
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_DIAGNOSTIC_BYTES = 4096;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Path> mounts = new ConcurrentHashMap<>();
    private final Set<String> pendingUnmounts = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("nfs-deferred-cleanup").factory());

    private NfsMountHelperMain() {
    }

    public static void main(String[] arguments) throws Exception {
        new NfsMountHelperMain().run();
    }

    private void run() throws Exception {
        prepareRootDirectory(SOCKET_DIRECTORY);
        prepareRootDirectory(MOUNT_ROOT);
        Files.deleteIfExists(SOCKET);
        cleanup.scheduleWithFixedDelay(this::retryUnmounts, 1, 1, TimeUnit.MINUTES);
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(SOCKET));
            assignToApplication(SOCKET, "rw-------");
            while (true) {
                SocketChannel client = server.accept();
                Thread.startVirtualThread(() -> handle(client));
            }
        } finally {
            cleanup.close();
            Files.deleteIfExists(SOCKET);
        }
    }

    private void handle(SocketChannel client) {
        try (client) {
            DataInputStream input = new DataInputStream(Channels.newInputStream(client));
            DataOutputStream output = new DataOutputStream(Channels.newOutputStream(client));
            switch (input.readUnsignedByte()) {
                case 1 -> mount(input, output);
                case 2 -> unmount(input, output);
                default -> failure(output, "Unsupported helper operation");
            }
            output.flush();
        } catch (IOException ignored) {
        }
    }

    private void mount(DataInputStream input, DataOutputStream output) throws IOException {
        String server = input.readUTF();
        String export = input.readUTF();
        String version = input.readUTF();
        try {
            validate(server, export, version);
            String token = token();
            Path mount = MOUNT_ROOT.resolve(token);
            Files.createDirectory(mount);
            assignToApplication(mount, "rwx------");
            CommandResult result = command(List.of("mount", "-t", "nfs", "-o",
                    "rw,hard,nosuid,nodev,noexec,tcp,sec=sys,vers=" + version, server + ":" + export, mount.toString()));
            if (!result.success()) {
                Files.deleteIfExists(mount);
                failure(output, "mount exited unsuccessfully" + result.diagnostic());
                return;
            }
            mounts.put(token, mount);
            output.writeBoolean(true);
            output.writeUTF(token);
            output.writeUTF(mount.toString());
        } catch (RuntimeException exception) {
            failure(output, exception.getMessage());
        }
    }

    private void unmount(DataInputStream input, DataOutputStream output) throws IOException {
        String token = input.readUTF();
        if (!TOKEN.matcher(token).matches()) {
            failure(output, "Invalid mount token");
            return;
        }
        Path mount = mounts.get(token);
        if (mount == null || !mount.equals(MOUNT_ROOT.resolve(token))) {
            failure(output, "Unknown mount token");
            return;
        }
        CommandResult result = unmount(mount);
        if (!result.success()) {
            pendingUnmounts.add(token);
            failure(output, "umount exited unsuccessfully" + result.diagnostic());
            return;
        }
        pendingUnmounts.remove(token);
        mounts.remove(token, mount);
        Files.deleteIfExists(mount);
        output.writeBoolean(true);
    }

    private void retryUnmounts() {
        for (String token : pendingUnmounts) {
            Path mount = mounts.get(token);
            if (mount == null) {
                pendingUnmounts.remove(token);
                continue;
            }
            try {
                if (!unmount(mount).success())
                    continue;

                pendingUnmounts.remove(token);
                mounts.remove(token, mount);
                Files.deleteIfExists(mount);
            } catch (RuntimeException | IOException ignored) {
            }
        }
    }

    private static CommandResult unmount(Path mount) { return command(List.of("umount", mount.toString())); }

    private static CommandResult command(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process running = process;
            ByteArrayOutputStream diagnostic = new ByteArrayOutputStream(MAX_DIAGNOSTIC_BYTES);
            Thread reader = Thread.startVirtualThread(() -> drain(running.getInputStream(), diagnostic));
            boolean finished = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished)
                process.destroyForcibly();

            reader.join();
            return new CommandResult(finished && process.exitValue() == 0, sanitized(diagnostic));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null)
                process.destroyForcibly();

            return new CommandResult(false, " interrupted");
        } catch (IOException exception) {
            return new CommandResult(false, " could not start");
        }
    }

    private static void drain(InputStream source, ByteArrayOutputStream target) {
        byte[] buffer = new byte[1024];
        try (source) {
            int read;
            while ((read = source.read(buffer)) >= 0) {
                int remaining = MAX_DIAGNOSTIC_BYTES - target.size();
                if (remaining > 0)
                    target.write(buffer, 0, Math.min(read, remaining));
            }
        } catch (IOException ignored) {
        }
    }

    private static String sanitized(ByteArrayOutputStream value) {
        String diagnostic = value.toString(java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("[\\r\\n]+", " ").trim();
        return diagnostic.isEmpty() ? "" : ": " + diagnostic;
    }

    private static void validate(String server, String export, String version) {
        if (!SERVER.matcher(server).matches())
            throw new IllegalArgumentException("Invalid NFS server");
        if (export.length() > 1024 || !export.startsWith("/") || export.contains("..") || export.contains(",")
                || export.chars().anyMatch(character -> Character.isWhitespace(character) || Character.isISOControl(character)))
            throw new IllegalArgumentException("Invalid NFS export");
        if (!version.equals("3") && !version.equals("4"))
            throw new IllegalArgumentException("Invalid NFS version");
        if (server.toLowerCase(java.util.Locale.ROOT).contains("sec=krb5") || export.toLowerCase(java.util.Locale.ROOT).contains("sec=krb5"))
            throw new IllegalArgumentException("Kerberos NFS security is not supported");
    }

    private String token() {
        byte[] value = new byte[16];
        String token;
        do {
            random.nextBytes(value);
            token = HexFormat.of().formatHex(value);
        } while (mounts.containsKey(token) || Files.exists(MOUNT_ROOT.resolve(token), LinkOption.NOFOLLOW_LINKS));
        return token;
    }

    private static void prepareRootDirectory(Path path) throws IOException {
        Files.createDirectories(path);
        assign(path, "root", "root", "rwx--x--x");
    }

    private static void assignToApplication(Path path, String permissions) throws IOException {
        assign(path, "application", "application", permissions);
    }

    private static void assign(Path path, String userName, String groupName, String permissions) throws IOException {
        UserPrincipalLookupService lookup = path.getFileSystem().getUserPrincipalLookupService();
        UserPrincipal user = lookup.lookupPrincipalByName(userName);
        GroupPrincipal group = lookup.lookupPrincipalByGroupName(groupName);
        Files.setOwner(path, user);
        Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class).setGroup(group);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    }

    private static void failure(DataOutputStream output, String message) throws IOException {
        output.writeBoolean(false);
        output.writeUTF(message == null || message.isBlank() ? "NFS helper operation failed" : message);
    }

    private record CommandResult(boolean success, String diagnostic) { }
}
