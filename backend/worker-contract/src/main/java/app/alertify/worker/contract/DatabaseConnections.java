package app.alertify.worker.contract;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;

/**
 * Opens standard {@link java.sql.Connection}s from {@link DatabaseCredentials}.
 * Credentials are passed as driver properties rather than embedded in the URL,
 * so the URL can be logged safely. Drivers are discovered through
 * {@link DriverManager}; the worker runtime ships the supported ones.
 */
public final class DatabaseConnections {

    private DatabaseConnections() {
    }

    public static String jdbcUrl(DatabaseCredentials credentials) {
        String options = credentials.options();
        return switch (credentials.engine()) {
            case POSTGRESQL -> withQuery("jdbc:postgresql://" + hostPort(credentials) + "/" + credentials.database(), options);
            case MARIADB -> withQuery("jdbc:mariadb://" + hostPort(credentials) + "/" + credentials.database(), options);
            case SQL_SERVER -> withSemicolons("jdbc:sqlserver://" + hostPort(credentials) + ";databaseName=" + credentials.database(), options);
            case ORACLE -> withQuery("jdbc:oracle:thin:@//" + hostPort(credentials) + "/" + credentials.database(), options);
            case OTHER -> options;
        };
    }

    public static Connection open(DatabaseCredentials credentials) throws SQLException {
        return open(credentials, null);
    }

    /**
     * Opens a connection applying {@code loginTimeout} through the property each
     * driver understands, without touching the global
     * {@link DriverManager#setLoginTimeout(int)}. The timeout is ignored for
     * {@link DatabaseEngine#OTHER}.
     */
    public static Connection open(DatabaseCredentials credentials, Duration loginTimeout) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", credentials.username());
        properties.setProperty("password", credentials.password());
        if (loginTimeout != null && !loginTimeout.isNegative() && !loginTimeout.isZero())
            applyLoginTimeout(properties, credentials.engine(), loginTimeout);

        return DriverManager.getConnection(jdbcUrl(credentials), properties);
    }

    private static void applyLoginTimeout(Properties properties, DatabaseEngine engine, Duration timeout) {
        long seconds = Math.max(1, timeout.toSeconds());
        long millis = Math.max(1, timeout.toMillis());
        switch (engine) {
            case POSTGRESQL, SQL_SERVER -> properties.setProperty("loginTimeout", Long.toString(seconds));
            case ORACLE -> properties.setProperty("oracle.net.CONNECT_TIMEOUT", Long.toString(millis));
            case MARIADB -> properties.setProperty("connectTimeout", Long.toString(millis));
            case OTHER -> { }
        }
    }

    private static String hostPort(DatabaseCredentials credentials) {
        return credentials.host() + ":" + credentials.port();
    }

    private static String withQuery(String url, String options) {
        if (options == null)
            return url;

        return url + (options.startsWith("?") ? "" : "?") + options;
    }

    private static String withSemicolons(String url, String options) {
        if (options == null)
            return url;

        return url + (options.startsWith(";") ? "" : ";") + options;
    }
}
