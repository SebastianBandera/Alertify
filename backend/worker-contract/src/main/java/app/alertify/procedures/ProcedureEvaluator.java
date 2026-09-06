package app.alertify.procedures;

import tools.jackson.databind.JsonNode;

/** Contract implemented by executable procedure templates. */
@FunctionalInterface
public interface ProcedureEvaluator {

    JsonNode execute(ProcedureExecutionContext context) throws Exception;
}
