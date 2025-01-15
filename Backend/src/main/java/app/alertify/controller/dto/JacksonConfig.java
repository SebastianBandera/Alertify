package app.alertify.controller.dto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

@Configuration
public class JacksonConfig {

    @Value("${spring.jpa.properties.hibernate.jdbc.time_zone:}")
    private String appTimeZone;

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer customizer() {
        return builder -> {
            if (appTimeZone != null && !appTimeZone.trim().isEmpty()) {
                builder.timeZone(TimeZone.getTimeZone(appTimeZone));
            }
        };
    }
}