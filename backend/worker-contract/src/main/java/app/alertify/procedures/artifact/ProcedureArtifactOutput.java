package app.alertify.procedures.artifact;

import java.io.IOException;
import java.io.OutputStream;

/** Execution-scoped destination for one transient procedure artifact. */
@FunctionalInterface
public interface ProcedureArtifactOutput {

    OutputStream openStream(String fileName, String mediaType) throws IOException;
}
