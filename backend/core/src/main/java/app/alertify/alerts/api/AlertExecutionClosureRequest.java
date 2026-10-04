package app.alertify.alerts.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AlertExecutionClosureRequest(@NotNull Boolean closed, @Size(max = 2000) String note) { }
