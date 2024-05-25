package app.watchful.control;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import app.watchful.control.generic.SQLThreshold;

@Configuration
public class AppControlBeans {

	@Bean(name = "sql_threshold")
	@Scope("prototype")
    public SQLThreshold myBean() {
        return new SQLThreshold();
    }
}
