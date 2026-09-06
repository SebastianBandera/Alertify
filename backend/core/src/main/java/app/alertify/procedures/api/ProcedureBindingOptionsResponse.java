package app.alertify.procedures.api;

import java.util.List;

public record ProcedureBindingOptionsResponse(
    List<ProcedureBindingOptionResponse> configurations,
    List<ProcedureBindingOptionResponse> secrets,
    List<ProcedureBindingOptionResponse> procedures
) {
}
