package app.alertify.controller.dto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    @Value("${spring.jpa.properties.hibernate.jdbc.time_zone:}")
    private String appTimeZone;

    /*@Bean
    public Jackson2ObjectMapperBuilderCustomizer customizer() {
        return builder -> {
            if (appTimeZone != null && !appTimeZone.trim().isEmpty()) {
                builder.timeZone(TimeZone.getTimeZone(appTimeZone));
            }
        };
    }*/
}