package app.alertify.procedures;

import java.util.UUID;

public class MissingArtifactInputException extends ProcedureExecutionException {
    public MissingArtifactInputException(String message) {
        super(message);
    }

    public MissingArtifactInputException(UUID executionId, String message) {
        super(executionId, message);
    }
}
