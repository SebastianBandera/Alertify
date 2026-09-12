package app.alertify.procedures.templates.devtools;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import app.alertify.procedures.ProcedureEvaluator;
import app.alertify.procedures.ProcedureExecutionContext;
import app.alertify.procedures.template.annotation.ProcedureParameter;
import app.alertify.procedures.template.annotation.ProcedureTemplate;
import app.alertify.procedures.template.annotation.ProcedureTemplateTag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Development template that simulates a potentially long-running task. */
@ProcedureTemplate(
    nameKey = "procedures.template.devtools.simulatedLongRunning.name",
    descriptionKey = "procedures.template.devtools.simulatedLongRunning.description",
    tags = @ProcedureTemplateTag(nameKey = "procedures.templateTag.development"),
    sourcePath = "app/alertify/procedures/templates/devtools/SimulatedLongRunningProcedureTemplate.java"
)
public final class SimulatedLongRunningProcedureTemplate implements ProcedureEvaluator {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ProcedureParameter(
        labelKey = "procedures.template.devtools.simulatedLongRunning.sleepMilliseconds",
        descriptionKey = "procedures.template.devtools.simulatedLongRunning.sleepMillisecondsDescription",
        defaultValue = "10000",
        order = 1
    )
    private final long sleepMilliseconds;

    @ProcedureParameter(
        labelKey = "procedures.template.devtools.simulatedLongRunning.randomInitialDelayEnabled",
        descriptionKey = "procedures.template.devtools.simulatedLongRunning.randomInitialDelayEnabledDescription",
        options = { "false", "true" },
        bindingAllowed = false,
        defaultValue = "false",
        order = 2
    )
    private final boolean randomInitialDelayEnabled;

    @ProcedureParameter(
        labelKey = "procedures.template.devtools.simulatedLongRunning.randomInitialDelayMinSeconds",
        descriptionKey = "procedures.template.devtools.simulatedLongRunning.randomInitialDelayMinSecondsDescription",
        defaultValue = "0",
        order = 3
    )
    private final long randomInitialDelayMinSeconds;

    @ProcedureParameter(
        labelKey = "procedures.template.devtools.simulatedLongRunning.randomInitialDelayMaxSeconds",
        descriptionKey = "procedures.template.devtools.simulatedLongRunning.randomInitialDelayMaxSecondsDescription",
        defaultValue = "5",
        order = 4
    )
    private final long randomInitialDelayMaxSeconds;

    public SimulatedLongRunningProcedureTemplate(long sleepMilliseconds, boolean randomInitialDelayEnabled, long randomInitialDelayMinSeconds, long randomInitialDelayMaxSeconds) {
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
    public JsonNode execute(ProcedureExecutionContext context) throws InterruptedException {
        Instant startedAt = Instant.now();
        long randomInitialDelaySeconds = randomInitialDelayEnabled ? randomInitialDelaySeconds() : 0;
        if (randomInitialDelaySeconds > 0)
            Thread.sleep(Math.multiplyExact(randomInitialDelaySeconds, 1_000));

        Thread.sleep(sleepMilliseconds);
        Instant finishedAt = Instant.now();
        return JSON.createObjectNode()
                .put("sleepMilliseconds", sleepMilliseconds)
                .put("randomInitialDelayEnabled", randomInitialDelayEnabled)
                .put("randomInitialDelayMinSeconds", randomInitialDelayMinSeconds)
                .put("randomInitialDelayMaxSeconds", randomInitialDelayMaxSeconds)
                .put("randomInitialDelaySeconds", randomInitialDelaySeconds)
                .put("startedAt", startedAt.toString())
                .put("finishedAt", finishedAt.toString());
    }

    private long randomInitialDelaySeconds() {
        if (randomInitialDelayMinSeconds == randomInitialDelayMaxSeconds)
            return randomInitialDelayMinSeconds;

        return ThreadLocalRandom.current().nextLong(randomInitialDelayMinSeconds, Math.addExact(randomInitialDelayMaxSeconds, 1));
    }
}
