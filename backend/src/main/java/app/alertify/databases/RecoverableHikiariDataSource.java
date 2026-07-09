package app.alertify.databases;

import java.io.Closeable;
import com.zaxxer.hikari.HikariDataSource;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RecoverableHikiariDataSource extends HikariDataSource implements Closeable {
	
	private static final Logger log = LoggerFactory.getLogger(RecoverableHikiariDataSource.class);

	private volatile DataSource dataSource = null;

	private final String NAME;
	private final Supplier<HikariDataSource> SUPPLIER;
	
	private synchronized void retryConnection() {
		if(dataSource == null) {
			try {
				log.info("Retrying connection " + this.NAME);
				dataSource = SUPPLIER.get();
				log.info("Successful retrying connection " + this.NAME);
			} catch (Exception e) {
				throw new RuntimeException("Connection failed " + this.NAME);
			}
		}
	}
	
	public RecoverableHikiariDataSource(String name, Supplier<HikariDataSource> supplier) {
		this.NAME = Objects.requireNonNull(name, "Name required");
		this.SUPPLIER = Objects.requireNonNull(supplier, "Supplier required");
	}
	
	public DataSource getDataSourceRecovered() {
		return this.dataSource;
	}

	public PrintWriter getLogWriter() throws SQLException {
		retryConnection();
		return dataSource.getLogWriter();
	}

	public <T> T unwrap(Class<T> iface) throws SQLException {
		retryConnection();
		return dataSource.unwrap(iface);
	}

	public void setLogWriter(PrintWriter out) throws SQLException {
		retryConnection();
		dataSource.setLogWriter(out);
	}

	public boolean isWrapperFor(Class<?> iface) throws SQLException {
		retryConnection();
		return dataSource.isWrapperFor(iface);
	}

	public Connection getConnection() throws SQLException {
		retryConnection();
		return dataSource.getConnection();
	}

	public void setLoginTimeout(int seconds) throws SQLException {
		retryConnection();
		dataSource.setLoginTimeout(seconds);
	}

	public Connection getConnection(String username, String password) throws SQLException {
		retryConnection();
		return dataSource.getConnection(username, password);
	}

	public int getLoginTimeout() throws SQLException {
		retryConnection();
		return dataSource.getLoginTimeout();
	}

	public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
		retryConnection();
		return dataSource.getParentLogger();
	}
}
