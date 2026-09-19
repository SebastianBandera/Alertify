package app.alertify.realtime;

import org.springframework.security.core.Authentication;

import tools.jackson.databind.JsonNode;

/** Handles one authenticated request received through the administrative event channel. */
public interface AdminRequestHandler {

    String requestName();

    JsonNode handle(Authentication authentication, JsonNode payload);
}
