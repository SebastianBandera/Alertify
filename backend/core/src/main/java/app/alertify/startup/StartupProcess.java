package app.alertify.startup;

import org.springframework.stereotype.Component;

/**
 * Extension point for startup tasks that must complete before startup is
 * considered successful. It is intentionally empty until such tasks exist.
 */
@Component
public class StartupProcess {

    public void run() {
        // Add required synchronous startup tasks here in the future.
    }
}
