package app.alertify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@EnableCaching
@EnableMethodSecurity
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class AlertifyApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlertifyApplication.class, args);
    }
}
