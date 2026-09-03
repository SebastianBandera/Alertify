package app.alertify.alerts.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateKey;

class TcpConnectionAlertTemplateTest {

    @Test
    void derivesItsAlternateKeyFromThePackageAndClassName() {
        assertEquals(
            TcpConnectionAlertTemplate.class.getName(),
            AlertTemplateKey.of(TcpConnectionAlertTemplate.class)
        );
    }

    @Test
    void declaresLocalizedTemplateAndParameterMetadata() throws ReflectiveOperationException {
        AlertTemplate metadata = TcpConnectionAlertTemplate.class.getAnnotation(AlertTemplate.class);

        assertEquals("alerts.template.tcpConnection.name", metadata.nameKey());
        assertEquals("alerts.template.tcpConnection.description", metadata.descriptionKey());

        AlertParameter host = parameter("host");
        AlertParameter port = parameter("port");
        AlertParameter timeout = parameter("timeoutSeconds");
        assertEquals(1, host.order());
        assertTrue(host.bindingAllowed());
        assertEquals(2, port.order());
        assertTrue(port.bindingAllowed());
        assertEquals(3, timeout.order());
        assertTrue(timeout.bindingAllowed());
        assertEquals("3", timeout.defaultValue());
        assertEquals(List.of("1", "3", "5", "10", "30"), List.of(timeout.options()));
    }

    @Test
    void resolvesTheHostAndReportsAnOpenTcpPort() throws Exception {
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            AlertExecutionContext context = new AlertExecutionContext();

            AlertResult result = new TcpConnectionAlertTemplate(
                "127.0.0.1", server.getLocalPort(), 1
            ).evaluate(context);

            assertEquals(AlertExecutionStatus.SUCCESS, result.status());
            assertEquals(true, result.statusMessage().get("hostnameResolved"));
            assertEquals(true, result.statusMessage().get("portOpen"));
            assertEquals(true, result.statusMessage().get("reachable"));
            assertEquals("127.0.0.1", result.statusMessage().get("connectedAddress"));
            assertEquals(1, result.statusMessage().get("timeoutSeconds"));
            assertInstanceOf(Long.class, result.statusMessage().get("dnsResolutionMs"));
            assertInstanceOf(Long.class, result.statusMessage().get("connectMs"));
            assertInstanceOf(Long.class, result.statusMessage().get("totalLatencyMs"));
            assertTrue(context.getState().contains("reachable=true"));
        }
    }

    @Test
    void reportsAClosedPortAsAWarning() throws Exception {
        int closedPort;
        try (ServerSocket server = new ServerSocket(0)) {
            closedPort = server.getLocalPort();
        }

        AlertExecutionContext context = new AlertExecutionContext();
        AlertResult result = new TcpConnectionAlertTemplate(
            "127.0.0.1", closedPort, 1
        ).evaluate(context);

        assertEquals(AlertExecutionStatus.WARN, result.status());
        assertEquals(true, result.statusMessage().get("hostnameResolved"));
        assertEquals(false, result.statusMessage().get("portOpen"));
        assertEquals(false, result.statusMessage().get("reachable"));
        assertEquals("connect_failed", result.statusMessage().get("failureReason"));
        assertFalse(context.getState().isBlank());
        assertTrue(context.getState().contains("reachable=false"));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new TcpConnectionAlertTemplate(" ", 5432, 3));
        assertThrows(IllegalArgumentException.class, () -> new TcpConnectionAlertTemplate("database", 0, 3));
        assertThrows(IllegalArgumentException.class, () -> new TcpConnectionAlertTemplate("database", 65_536, 3));
        assertThrows(IllegalArgumentException.class, () -> new TcpConnectionAlertTemplate("database", 5432, 0));
        assertThrows(IllegalArgumentException.class, () -> new TcpConnectionAlertTemplate("database", 5432, 3_601));
    }

    private static AlertParameter parameter(String fieldName) throws ReflectiveOperationException {
        Field field = TcpConnectionAlertTemplate.class.getDeclaredField(fieldName);
        return field.getAnnotation(AlertParameter.class);
    }
}
