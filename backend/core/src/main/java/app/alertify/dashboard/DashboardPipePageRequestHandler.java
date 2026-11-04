package app.alertify.dashboard;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import app.alertify.realtime.AdminRequestHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class DashboardPipePageRequestHandler implements AdminRequestHandler {
    private final DashboardPipeCardService cards;
    private final JsonMapper mapper;

    public DashboardPipePageRequestHandler(DashboardPipeCardService cards, JsonMapper mapper) {
        this.cards = cards;
        this.mapper = mapper;
    }

    @Override
    public String requestName() { return "DASHBOARD_PIPE_PAGE"; }

    @Override
    public JsonNode handle(Authentication authentication, JsonNode payload) { return mapper.valueToTree(page(payload)); }

    public JsonNode handleForViewer(JsonNode payload) { return mapper.valueToTree(page(payload).forViewer()); }

    private DashboardPipeCardService.PipePage page(JsonNode payload) {
        return cards.page(number(payload, "page", 0), number(payload, "size", 12));
    }

    private static int number(JsonNode payload, String field, int fallback) {
        JsonNode value = payload == null ? null : payload.get(field);
        return value != null && value.isIntegralNumber() ? value.intValue() : fallback;
    }
}
