package app.alertify.worker.contract;

/**
 * Database engines a {@code DB_SECRET} can describe. {@link #OTHER} covers any
 * JDBC-compatible engine whose full connection URL is supplied by the user.
 */
public enum DatabaseEngine {

    POSTGRESQL,
    MARIADB,
    SQL_SERVER,
    ORACLE,
    OTHER
}
