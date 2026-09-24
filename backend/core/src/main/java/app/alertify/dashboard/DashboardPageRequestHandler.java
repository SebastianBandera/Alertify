package app.alertify.dashboard;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import app.alertify.realtime.AdminRequestHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serves the dashboard snapshot page by page over the administrative event
 * channel, and the viewer projection of it over the viewer channel.
 */
@Component
public class DashboardPageRequestHandler implements AdminRequestHandler {

    static final String REQUEST_NAME = "DASHBOARD_PAGE";

    private final DashboardCardService cardService;
    private final JsonMapper jsonMapper;

    public DashboardPageRequestHandler(DashboardCardService cardService, JsonMapper jsonMapper) {
        this.cardService = cardService;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public String requestName() {
        return REQUEST_NAME;
    }

    @Override
    public JsonNode handle(Authentication authentication, JsonNode payload) {
        return jsonMapper.valueToTree(page(payload));
    }

    public JsonNode handleForViewer(JsonNode payload) {
        return jsonMapper.valueToTree(page(payload).forViewer());
    }

    private DashboardPageResponse page(JsonNode payload) {
        int page = intField(payload, "page", 0);
        int size = intField(payload, "size", DashboardCardService.DEFAULT_PAGE_SIZE);
        return cardService.page(page, size);
    }

    private static int intField(JsonNode payload, String field, int fallback) {
        JsonNode node = payload == null ? null : payload.get(field);
        return node != null && node.isIntegralNumber() ? node.intValue() : fallback;
    }
}
