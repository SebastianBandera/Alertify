package app.alertify.ai.api;

import app.alertify.alerts.template.annotation.AlertParameterSource;

public enum AiProcedureParameterSource {
    TEXT(AlertParameterSource.TEXT),
    CONFIGURATION(AlertParameterSource.CONFIGURATION),
    SECRET(AlertParameterSource.SECRET),
    PROCEDURE(AlertParameterSource.PROCEDURE),
    PIPE(AlertParameterSource.PIPE);

    private final AlertParameterSource source;

    AiProcedureParameterSource(AlertParameterSource source) {
        this.source = source;
    }

    AlertParameterSource source() { return source; }
}
