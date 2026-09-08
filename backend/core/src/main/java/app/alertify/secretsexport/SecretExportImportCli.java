package app.alertify.secretsexport;

import java.io.Console;
import java.nio.file.Path;
import java.util.Arrays;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Standalone entry point for the secrets export/import tool (see
 * {@code secrets-tool.sh}). It is never wired into the running application
 * and has no HTTP surface: it can only be started from inside the container
 * by loading this class through Spring Boot's {@code PropertiesLauncher}
 * against the same fat jar, which boots the narrow, non-web context declared
 * in {@link SecretExportImportConfiguration} instead of {@code AlertifyApplication}.
 */
public final class SecretExportImportCli {

    private static final Path EXPORT_DIRECTORY = Path.of("/application/export");

    private SecretExportImportCli() {
    }

    public static void main(String[] args) {
        boolean isImport = args.length > 0 && "import".equalsIgnoreCase(args[0]);
        boolean isExport = args.length > 0 && "export".equalsIgnoreCase(args[0]);
        if (!isImport && !isExport)
            usageError();
        if (isImport && args.length < 2)
            usageError();

        char[] password = isImport ? readPasswordOrExit() : null;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SecretExportImportConfiguration.class)
                .web(WebApplicationType.NONE)
                .profiles(SecretExportImportConfiguration.PROFILE)
                .run(args)) {
            SecretExportImportService service = context.getBean(SecretExportImportService.class);
            if (isImport)
                runImport(service, Path.of(args[1]), password);
            else
                runExport(service);
        } catch (RuntimeException exception) {
            System.err.println("ERROR: " + exception.getMessage());
            System.exit(1);
        } finally {
            if (password != null)
                Arrays.fill(password, '\0');
        }
    }

    private static void runExport(SecretExportImportService service) {
        SecretExportImportService.ExportResult result = service.export(EXPORT_DIRECTORY);
        System.out.println();
        System.out.println("Exported " + result.count() + " secret(s) to: " + result.file());
        System.out.println();
        System.out.println("MASTER PASSWORD (shown only once, write it down now):");
        System.out.println("  " + result.password());
        System.out.println();
        System.out.println("This path is not a Docker volume: copy the file out now, e.g.");
        System.out.println("  docker cp <container>:" + result.file() + " ./");
        System.out.println("before the container is rebuilt or recreated, or it will be lost.");
    }

    private static void runImport(SecretExportImportService service, Path file, char[] password) {
        SecretExportImportService.ImportResult result = service.importFrom(file, password);
        System.out.println();
        System.out.println("Created " + result.created().size() + " secret(s): " + result.created());
        System.out.println("Skipped " + result.skipped().size() + " secret(s) that already existed: " + result.skipped());
    }

    private static char[] readPasswordOrExit() {
        Console console = System.console();
        if (console == null) {
            System.err.println("ERROR: no console available; run with 'docker exec -it' so the password can be entered interactively.");
            System.exit(1);
        }
        return console.readPassword("Archive password: ");
    }

    private static void usageError() {
        System.err.println("Usage: secrets-tool.sh export");
        System.err.println("       secrets-tool.sh import <path-to-zip>");
        System.exit(1);
    }
}
