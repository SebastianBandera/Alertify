package app.alertify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class AlertifyApplication {

	public static void main(String[] args) {
		SpringApplication.run(AlertifyApplication.class, args);
	}

}
