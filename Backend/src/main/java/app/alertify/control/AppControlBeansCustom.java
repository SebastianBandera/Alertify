package app.alertify.control;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppControlBeansCustom {

	@SuppressWarnings("unused")
	@Autowired(required = true)
	private DataSource localDataSource;
	
	//Add here
}
