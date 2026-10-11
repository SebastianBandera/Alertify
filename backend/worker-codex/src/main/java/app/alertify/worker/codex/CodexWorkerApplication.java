package app.alertify.worker.codex;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(CodexWorkerProperties.class)
@EnableScheduling
public class CodexWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodexWorkerApplication.class, args);
    }
}
