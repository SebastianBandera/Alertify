package app.watchful.control;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import app.watchful.control.generic.SQLThreshold;
import app.watchful.control.generic.WebRequest;

@Configuration
public class AppControlBeans {

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
}
