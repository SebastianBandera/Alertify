package app.alertify.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class DashboardPageRequestHandlerTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    @Mock private DashboardCardService cardService;

    @Test
    void usesDefaultsWhenThePayloadIsMissing() {
        DashboardPageResponse response = new DashboardPageResponse(List.of(), new DashboardPageResponse.PageMetadata(12, 0, 0, 0));
        when(cardService.page(0, DashboardCardService.DEFAULT_PAGE_SIZE)).thenReturn(response);

        JsonNode result = handler().handle(null, null);

        assertThat(handler().requestName()).isEqualTo("DASHBOARD_PAGE");
        assertThat(result.get("page").get("size").intValue()).isEqualTo(12);
        assertThat(result.get("content").isArray()).isTrue();
    }

    @Test
    void forwardsPageAndSizeFromThePayload() {
        DashboardPageResponse response = new DashboardPageResponse(List.of(), new DashboardPageResponse.PageMetadata(20, 2, 45, 3));
        when(cardService.page(2, 20)).thenReturn(response);

        JsonNode result = handler().handle(null, jsonMapper.readTree("{\"page\":2,\"size\":20}"));

        verify(cardService).page(2, 20);
        assertThat(result.get("page").get("totalElements").longValue()).isEqualTo(45);
    }

    private DashboardPageRequestHandler handler() {
        return new DashboardPageRequestHandler(cardService, jsonMapper);
    }
}
