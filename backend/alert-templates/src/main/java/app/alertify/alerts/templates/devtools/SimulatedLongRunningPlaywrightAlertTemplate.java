package app.alertify.alerts.templates.devtools;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateTag;
import app.alertify.worker.contract.WorkerCapability;

/**
 * Development template that simulates a potentially long-running task on a
 * worker with Playwright capability, without using Playwright itself.
 */
@AlertTemplate(
    nameKey = "alerts.template.devtools.simulatedLongRunningPlaywright.name",
    descriptionKey = "alerts.template.devtools.simulatedLongRunningPlaywright.description",
    tags = @AlertTemplateTag(nameKey = "alerts.templateTag.development"),
    capability = WorkerCapability.PLAYWRIGHT,
    sourcePath = "app/alertify/alerts/templates/devtools/SimulatedLongRunningPlaywrightAlertTemplate.java"
)
public final class SimulatedLongRunningPlaywrightAlertTemplate implements AlertEvaluator {

    @AlertParameter(
        labelKey = "alerts.template.devtools.simulatedLongRunningPlaywright.sleepMilliseconds",
        descriptionKey = "alerts.template.devtools.simulatedLongRunningPlaywright.sleepMillisecondsDescription",
        defaultValue = "10000",
        order = 1
    )
    private final long sleepMilliseconds;

    @AlertParameter(
        labelKey = "alerts.template.devtools.simulatedLongRunningPlaywright.randomInitialDelayEnabled",
        descriptionKey = "alerts.template.devtools.simulatedLongRunningPlaywright.randomInitialDelayEnabledDescription",
        options = { "false", "true" },
        bindingAllowed = false,
        defaultValue = "false",
        order = 2
    )
    private final boolean randomInitialDelayEnabled;

    @AlertParameter(
        labelKey = "alerts.template.devtools.simulatedLongRunningPlaywright.randomInitialDelayMinSeconds",
        descriptionKey = "alerts.template.devtools.simulatedLongRunningPlaywright.randomInitialDelayMinSecondsDescription",
        defaultValue = "0",
        order = 3
    )
    private final long randomInitialDelayMinSeconds;

    @AlertParameter(
        labelKey = "alerts.template.devtools.simulatedLongRunningPlaywright.randomInitialDelayMaxSeconds",
        descriptionKey = "alerts.template.devtools.simulatedLongRunningPlaywright.randomInitialDelayMaxSecondsDescription",
        defaultValue = "5",
        order = 4
    )
    private final long randomInitialDelayMaxSeconds;

    public SimulatedLongRunningPlaywrightAlertTemplate(long sleepMilliseconds, boolean randomInitialDelayEnabled, long randomInitialDelayMinSeconds, long randomInitialDelayMaxSeconds) {
        if (sleepMilliseconds < 0)
            throw new IllegalArgumentException("sleepMilliseconds must not be negative");

        if (randomInitialDelayMinSeconds < 0)
            throw new IllegalArgumentException("randomInitialDelayMinSeconds must not be negative");

        if (randomInitialDelayMaxSeconds < randomInitialDelayMinSeconds)
            throw new IllegalArgumentException("randomInitialDelayMaxSeconds must be greater than or equal to randomInitialDelayMinSeconds");

        this.sleepMilliseconds = sleepMilliseconds;
        this.randomInitialDelayEnabled = randomInitialDelayEnabled;
        this.randomInitialDelayMinSeconds = randomInitialDelayMinSeconds;
        this.randomInitialDelayMaxSeconds = randomInitialDelayMaxSeconds;
    }

    @Override
    public AlertResult evaluate(AlertExecutionContext context) throws InterruptedException {
        Instant startedAt = Instant.now();
        long randomInitialDelaySeconds = randomInitialDelayEnabled ? randomInitialDelaySeconds() : 0;
        if (randomInitialDelaySeconds > 0)
            Thread.sleep(Math.multiplyExact(randomInitialDelaySeconds, 1_000));

        Thread.sleep(sleepMilliseconds);
        Instant finishedAt = Instant.now();
        Map<String, Object> statusMessage = new LinkedHashMap<>();
        statusMessage.put("sleepMilliseconds", sleepMilliseconds);
        statusMessage.put("randomInitialDelayEnabled", randomInitialDelayEnabled);
        statusMessage.put("randomInitialDelayMinSeconds", randomInitialDelayMinSeconds);
        statusMessage.put("randomInitialDelayMaxSeconds", randomInitialDelayMaxSeconds);
        statusMessage.put("randomInitialDelaySeconds", randomInitialDelaySeconds);
        statusMessage.put("startedAt", startedAt.toString());
        statusMessage.put("finishedAt", finishedAt.toString());
        context.setState("sleepMilliseconds=" + sleepMilliseconds + ";randomInitialDelaySeconds=" + randomInitialDelaySeconds);
        return AlertResult.success(statusMessage);
    }

    private long randomInitialDelaySeconds() {
        if (randomInitialDelayMinSeconds == randomInitialDelayMaxSeconds)
            return randomInitialDelayMinSeconds;

        return ThreadLocalRandom.current().nextLong(randomInitialDelayMinSeconds, Math.addExact(randomInitialDelayMaxSeconds, 1));
    }
}
