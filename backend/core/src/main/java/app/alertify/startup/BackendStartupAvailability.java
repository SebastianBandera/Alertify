package app.alertify.startup;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Gates functional traffic until all synchronous backend startup work succeeds. */
@Component
public class BackendStartupAvailability implements HealthIndicator {

    private final AtomicReference<State> state = new AtomicReference<>(State.INITIALIZING);

    public State state() {
        return state.get();
    }

    public boolean isReady() {
        return state() == State.READY;
    }

    public void migrating() {
        state.set(State.MIGRATING);
    }

    public void ready() {
        state.set(State.READY);
    }

    public void failed() {
        state.set(State.FAILED);
    }

    @Override
    public Health health() {
        State current = state();
        return current == State.READY
                ? Health.up().build()
                : Health.outOfService().build();
    }

    public enum State {
        INITIALIZING,
        MIGRATING,
        READY,
        FAILED
    }
}
