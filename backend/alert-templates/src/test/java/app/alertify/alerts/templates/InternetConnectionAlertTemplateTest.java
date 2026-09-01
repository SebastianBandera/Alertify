package app.alertify.alerts.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateKey;
import com.sun.net.httpserver.HttpServer;

class InternetConnectionAlertTemplateTest {

    @Test
    void derivesItsAlternateKeyFromThePackageAndClassName() {
        assertEquals(
            InternetConnectionAlertTemplate.class.getName(),
            AlertTemplateKey.of(InternetConnectionAlertTemplate.class)
        );
    }

    @Test
    void declaresLocalizedTemplateMetadata() {
        AlertTemplate metadata = InternetConnectionAlertTemplate.class.getAnnotation(AlertTemplate.class);

        assertEquals("alerts.template.internet.name", metadata.nameKey());
        assertEquals("alerts.template.internet.description", metadata.descriptionKey());
    }

    @Test
    void declaresEndpointAndTimeoutParameters() throws ReflectiveOperationException {
        AlertParameter endpoint = parameter("endpoint");
        AlertParameter timeout = parameter("timeoutSeconds");

        assertEquals(1, endpoint.order());
        assertTrue(endpoint.bindingAllowed());
        assertEquals("google", endpoint.defaultValue());
        assertEquals(List.of("google", "cloudflare"), List.of(endpoint.options()));

        assertEquals(2, timeout.order());
        assertTrue(timeout.bindingAllowed());
        assertEquals("3", timeout.defaultValue());
        assertEquals(List.of("1", "3", "5", "10"), List.of(timeout.options()));
    }

    @Test
    void includesTheConfiguredTimeoutInSuccessfulResults() throws Exception {
        HttpServer server = server(0);
        try {
            AlertResult result = template(server, 3).evaluate(new AlertExecutionContext());

            assertEquals(AlertExecutionStatus.SUCCESS, result.status());
            assertEquals(3L, result.statusMessage().get("timeoutSeconds"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsHttpTimeoutsAsWarnings() throws Exception {
        HttpServer server = server(2_000);
        try {
            AlertExecutionContext context = new AlertExecutionContext();
            AlertResult result = template(server, 1).evaluate(context);

            assertEquals(AlertExecutionStatus.WARN, result.status());
            assertEquals(1L, result.statusMessage().get("timeoutSeconds"));
            assertEquals(true, result.statusMessage().get("timedOut"));
            assertTrue(context.getState().contains("timedOut=true"));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server(long responseDelayMillis) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                if (responseDelayMillis > 0)
                    Thread.sleep(responseDelayMillis);

                exchange.sendResponseHeaders(204, -1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        return server;
    }

    private static InternetConnectionAlertTemplate template(HttpServer server, int timeoutSeconds) {
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        return new InternetConnectionAlertTemplate(endpoint, timeoutSeconds);
    }

    private static AlertParameter parameter(String fieldName) throws ReflectiveOperationException {
        Field field = InternetConnectionAlertTemplate.class.getDeclaredField(fieldName);
        return field.getAnnotation(AlertParameter.class);
    }
}
