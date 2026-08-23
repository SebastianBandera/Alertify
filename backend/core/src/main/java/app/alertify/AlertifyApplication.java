package app.alertify;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@EnableCaching
@EnableMethodSecurity
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
/**
 * Main Spring Boot entry point for the Alertify backend. Component, entity and
 * repository scanning starts from the {@code app.alertify} root package.
 */
public class AlertifyApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlertifyApplication.class, args);
    }
}
