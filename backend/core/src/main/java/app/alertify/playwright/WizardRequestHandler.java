package app.alertify.playwright;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import app.alertify.realtime.AdminRequestHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class WizardRequestHandler implements AdminRequestHandler {
    private final ObjectProvider<PlaywrightWizardService> service;
    private final JsonMapper json;
    public WizardRequestHandler(ObjectProvider<PlaywrightWizardService> service, JsonMapper json) { this.service = service; this.json = json; }
    @Override public String requestName() { return "PLAYWRIGHT_WIZARD"; }
    @Override public JsonNode handle(Authentication authentication, JsonNode payload) {
        try { return json.valueToTree(service.getObject().request(authentication, payload)); }
        catch (IllegalArgumentException exception) { return json.valueToTree(java.util.Map.of("requestError", exception.getMessage())); }
    }
}
