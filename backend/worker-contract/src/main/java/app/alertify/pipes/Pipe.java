package app.alertify.pipes;

import tools.jackson.databind.JsonNode;

/** Synchronous handle for one configured Pipe. */
@FunctionalInterface
public interface Pipe {

    JsonNode execute();
}
