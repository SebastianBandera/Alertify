package app.alertify.ai.chat;

import java.util.Map;

import org.springframework.test.util.ReflectionTestUtils;

/** Explicit prerequisite for legacy unit fixtures that isolate tool handling from objective assessment. */
final class AiChatTestAssessment {
    private AiChatTestAssessment() { }

    static void simpleWork(AiChatService service, Long turnId) {
        Map<?, ?> activeTurns = (Map<?, ?>) ReflectionTestUtils.getField(service, "activeTurns");
        Object active = activeTurns.get(turnId);
        if (active == null)
            throw new IllegalStateException("The test turn must be active before preparing its assessment");

        ReflectionTestUtils.setField(active, "objectiveAssessed", true);
        ReflectionTestUtils.setField(active, "objectiveAllowsWrites", true);
        ReflectionTestUtils.setField(active, "objectiveParticipates", false);
    }
}
