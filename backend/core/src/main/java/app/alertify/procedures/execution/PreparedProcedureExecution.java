package app.alertify.procedures.execution;

import java.util.List;

import app.alertify.worker.contract.WorkerCapability;

/**
 * Everything one procedure execution needs, read inside a single transaction so
 * the rest of the execution can run without touching the database.
 */
public record PreparedProcedureExecution(
    long procedureId,
    long procedureVersion,
    String procedureName,
    String templateClassName,
    WorkerCapability requiredCapability,
    boolean sensitiveResult,
    String sourceChecksum,
    String source,
    List<ResolvedProcedureParameter> parameters
) {
}
