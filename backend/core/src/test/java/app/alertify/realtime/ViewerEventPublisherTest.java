package app.alertify.realtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ViewerEventPublisherTest {

    @Mock private ViewerEventWebSocketHandler handler;

    @Test
    void publishesDashboardEvents() {
        ViewerEventPublisher publisher = new ViewerEventPublisher(handler, JsonMapper.builder().build());

        publisher.publish("DASHBOARD_ALERT_REMOVED", new RemovedAlert(7L));

        verify(handler).broadcastText(contains("\"name\":\"DASHBOARD_ALERT_REMOVED\""));
    }

    @Test
    void rejectsAdministrativeEvents() {
        ViewerEventPublisher publisher = new ViewerEventPublisher(handler, JsonMapper.builder().build());

        assertThatThrownBy(() -> publisher.publish("SYSTEM_STATUS", new Object()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported viewer event");
        verify(handler, never()).broadcastText(org.mockito.ArgumentMatchers.anyString());
    }

    private record RemovedAlert(long alertId) { }
}
