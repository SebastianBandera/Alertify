package app.alertify.procedures;

import tools.jackson.databind.JsonNode;

/**
 * Synchronous handle for one configured procedure instance.
 *
 * <p>The handle may be invoked zero, one, or many times. Every invocation is
 * independently dispatched and recorded.</p>
 */
@FunctionalInterface
public interface Procedure {

    JsonNode execute();
}
