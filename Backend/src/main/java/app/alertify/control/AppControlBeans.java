package app.alertify.control;

import javax.sql.DataSource;

import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import app.alertify.control.generic.SQLThreshold;
import app.alertify.control.generic.SQLWatch;
import app.alertify.control.generic.TestConnection;
import app.alertify.control.generic.WebRequest;
import app.alertify.control.generic.WebWatch;

@Configuration
public class AppControlBeans {

	private final DataSource localDataSource;
	
	public AppControlBeans(DataSource localDataSource) {
		this.localDataSource = localDataSource;
	}
	
	@PrototypeBean(name = "sql_threshold")
    public SQLThreshold SQLThreshold() {
        return new SQLThreshold();
    }

	@PrototypeBean(name = "web_request")
    public WebRequest WebRequest() {
        return new WebRequest();
    }

	@PrototypeBean(name = "sql_watch")
    public SQLWatch SQLWatch() {
		JdbcTemplate localJdbc = new JdbcTemplate(localDataSource);
        return new SQLWatch(localJdbc);
    }

	@PrototypeBean(name = "test_connection")
    public TestConnection TestConnection() {
        return new TestConnection();
    }

	@PrototypeBean(name = "web_watch")
    public WebWatch WebWatch() {
		JdbcTemplate localJdbc = new JdbcTemplate(localDataSource);
        return new WebWatch(localJdbc);
    }
}
