package app.watchful.control;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.jdbc.core.JdbcTemplate;

import app.watchful.control.generic.SQLThreshold;
import app.watchful.control.generic.SQLWatch;
import app.watchful.control.generic.TestConnection;
import app.watchful.control.generic.WebRequest;

@Configuration
public class AppControlBeans {

	@Autowired
	private DataSource localDataSource;
	
	@Bean(name = "sql_threshold")
	@Scope("prototype")
    public SQLThreshold SQLThreshold() {
        return new SQLThreshold();
    }

	@Bean(name = "web_request")
	@Scope("prototype")
    public WebRequest WebRequest() {
        return new WebRequest();
    }

	@Bean(name = "sql_watch")
	@Scope("prototype")
    public SQLWatch SQLWatch() {
		JdbcTemplate localJdbc = new JdbcTemplate(localDataSource);
        return new SQLWatch(localJdbc);
    }

	@Bean(name = "test_connection")
	@Scope("prototype")
    public TestConnection TestConnection() {
        return new TestConnection();
    }
}
