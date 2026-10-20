package app.alertify.ai.memory;

import app.alertify.codex.AiWorkerClient;

public final class AiMemoryAccess {
    private AiMemoryAccess() {
    }

    public static String denial(AiWorkerClient worker) {
        try {
            return worker.settings().getMemoryEnabled() ? null : "MEMORY_DISABLED";
        } catch (RuntimeException exception) {
            return "MEMORY_SETTINGS_UNAVAILABLE";
        }
    }

    public static void requireEnabled(AiWorkerClient worker) {
        String code = denial(worker);
        if (code != null)
            throw new Unavailable(code);
    }

    public static final class Unavailable extends RuntimeException {
        private final String code;

        public Unavailable(String code) {
            super("Memory access is disabled or its configuration could not be verified");
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
