package app.alertify.alerts.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateKey;
import app.alertify.alerts.templates.HttpsCertificateExpiryAlertTemplate.Endpoint;

class HttpsCertificateExpiryAlertTemplateTest {

    @Test
    void derivesItsAlternateKeyFromThePackageAndClassName() {
        assertEquals(
            HttpsCertificateExpiryAlertTemplate.class.getName(),
            AlertTemplateKey.of(HttpsCertificateExpiryAlertTemplate.class)
        );
    }

    @Test
    void declaresLocalizedTemplateMetadata() {
        AlertTemplate metadata = HttpsCertificateExpiryAlertTemplate.class.getAnnotation(AlertTemplate.class);

        assertEquals("alerts.template.httpsCertificate.name", metadata.nameKey());
        assertEquals("alerts.template.httpsCertificate.description", metadata.descriptionKey());
        assertEquals(
            "app/alertify/alerts/templates/HttpsCertificateExpiryAlertTemplate.java",
            metadata.sourcePath()
        );
    }

    @Test
    void declaresTheConfigurableParameters() throws ReflectiveOperationException {
        AlertParameter endpoint = parameter("endpoint");
        AlertParameter warningDays = parameter("warningDays");
        AlertParameter timeout = parameter("timeoutSeconds");
        AlertParameter verifyHostname = parameter("verifyHostname");

        assertEquals(1, endpoint.order());
        assertTrue(endpoint.bindingAllowed());
        assertEquals("", endpoint.defaultValue());
        assertEquals(List.of(), List.of(endpoint.options()));

        assertEquals(2, warningDays.order());
        assertTrue(warningDays.bindingAllowed());
        assertEquals("30", warningDays.defaultValue());
        assertEquals(List.of("7", "14", "30", "60", "90"), List.of(warningDays.options()));

        assertEquals(3, timeout.order());
        assertTrue(timeout.bindingAllowed());
        assertEquals("10", timeout.defaultValue());
        assertEquals(List.of("3", "5", "10", "30"), List.of(timeout.options()));

        assertEquals(4, verifyHostname.order());
        assertFalse(verifyHostname.bindingAllowed());
        assertEquals("true", verifyHostname.defaultValue());
        assertEquals(List.of("false", "true"), List.of(verifyHostname.options()));
    }

    @Test
    void rejectsInvalidConstructorArguments() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new HttpsCertificateExpiryAlertTemplate("example.com", -1, 10, true)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new HttpsCertificateExpiryAlertTemplate("example.com", 30, 0, true)
        );
    }

    @Test
    void parsesTheSupportedEndpointForms() {
        assertEquals(new Endpoint("ejemplo.com.uy", 443), HttpsCertificateExpiryAlertTemplate.parseEndpoint("https://ejemplo.com.uy"));
        assertEquals(new Endpoint("ejemplo.com.uy", 8443), HttpsCertificateExpiryAlertTemplate.parseEndpoint("https://ejemplo.com.uy:8443/estado"));
        assertEquals(new Endpoint("ejemplo.com.uy", 443), HttpsCertificateExpiryAlertTemplate.parseEndpoint("ejemplo.com.uy"));
        assertEquals(new Endpoint("ejemplo.com.uy", 8443), HttpsCertificateExpiryAlertTemplate.parseEndpoint("ejemplo.com.uy:8443"));
        assertEquals(new Endpoint("ejemplo.com.uy", 443), HttpsCertificateExpiryAlertTemplate.parseEndpoint("  ejemplo.com.uy/estado  "));
    }

    @Test
    void rejectsEndpointsWithoutAUsableHostOrPort() {
        assertThrows(IllegalArgumentException.class, () -> HttpsCertificateExpiryAlertTemplate.parseEndpoint("   "));
        assertThrows(IllegalArgumentException.class, () -> HttpsCertificateExpiryAlertTemplate.parseEndpoint("https:///estado"));
        assertThrows(IllegalArgumentException.class, () -> HttpsCertificateExpiryAlertTemplate.parseEndpoint(":8443"));
        assertThrows(IllegalArgumentException.class, () -> HttpsCertificateExpiryAlertTemplate.parseEndpoint("ejemplo.com.uy:puerto"));
        assertThrows(IllegalArgumentException.class, () -> HttpsCertificateExpiryAlertTemplate.parseEndpoint("ejemplo.com.uy:70000"));
    }

    @Test
    void matchesHostnamesAgainstSubjectAlternativeNames() {
        assertTrue(HttpsCertificateExpiryAlertTemplate.hostnameMatches("ejemplo.com.uy", List.of("otro.com", "ejemplo.com.uy"), ""));
        assertTrue(HttpsCertificateExpiryAlertTemplate.hostnameMatches("EJEMPLO.com.uy", List.of("ejemplo.com.uy"), ""));
        assertTrue(HttpsCertificateExpiryAlertTemplate.hostnameMatches("api.ejemplo.com.uy", List.of("*.ejemplo.com.uy"), ""));
        assertFalse(HttpsCertificateExpiryAlertTemplate.hostnameMatches("a.b.ejemplo.com.uy", List.of("*.ejemplo.com.uy"), ""));
        assertFalse(HttpsCertificateExpiryAlertTemplate.hostnameMatches("ejemplo.com.uy", List.of("*.ejemplo.com.uy"), ""));
        assertFalse(HttpsCertificateExpiryAlertTemplate.hostnameMatches("otro.com.uy", List.of("ejemplo.com.uy"), ""));
    }

    @Test
    void fallsBackToTheCommonNameWhenThereAreNoSubjectAlternativeNames() {
        assertTrue(HttpsCertificateExpiryAlertTemplate.hostnameMatches("ejemplo.com.uy", List.of(), "ejemplo.com.uy"));
        assertFalse(HttpsCertificateExpiryAlertTemplate.hostnameMatches("ejemplo.com.uy", List.of(), "otro.com.uy"));
        assertFalse(HttpsCertificateExpiryAlertTemplate.hostnameMatches("ejemplo.com.uy", List.of(), ""));
        assertFalse(HttpsCertificateExpiryAlertTemplate.hostnameMatches("ejemplo.com.uy", List.of("otro.com.uy"), "ejemplo.com.uy"));
    }

    @Test
    void readsTheCommonNameFromADistinguishedName() {
        assertEquals("ejemplo.com.uy", HttpsCertificateExpiryAlertTemplate.commonName("CN=ejemplo.com.uy,O=Ejemplo,C=UY"));
        assertEquals("ejemplo.com.uy", HttpsCertificateExpiryAlertTemplate.commonName("O=Ejemplo, cn=ejemplo.com.uy"));
        assertEquals("", HttpsCertificateExpiryAlertTemplate.commonName("O=Ejemplo,C=UY"));
        assertEquals("", HttpsCertificateExpiryAlertTemplate.commonName(null));
    }

    @Test
    void labelsConnectionFailuresWithoutLosingTheOriginalType() {
        Endpoint target = new Endpoint("ejemplo.com.uy", 8443);

        UnknownHostException unknownHost = new UnknownHostException("ejemplo.com.uy");
        Exception described = HttpsCertificateExpiryAlertTemplate.describe(unknownHost, target);
        assertEquals(UnknownHostException.class, described.getClass());
        assertEquals("(unknown_host) ejemplo.com.uy:8443 could not be resolved", described.getMessage());
        assertSame(unknownHost, described.getCause());

        Exception timeout = HttpsCertificateExpiryAlertTemplate.describe(new SocketTimeoutException("Read timed out"), target);
        assertEquals(SocketTimeoutException.class, timeout.getClass());
        assertEquals("(timeout) ejemplo.com.uy:8443 timed out during the TLS handshake", timeout.getMessage());

        Exception refused = HttpsCertificateExpiryAlertTemplate.describe(new ConnectException("Connection refused"), target);
        assertEquals(ConnectException.class, refused.getClass());
        assertTrue(refused.getMessage().startsWith("(connect_failed) ejemplo.com.uy:8443 refused the connection"));

        Exception handshake = HttpsCertificateExpiryAlertTemplate.describe(new SSLHandshakeException("PKIX path building failed"), target);
        assertEquals(SSLHandshakeException.class, handshake.getClass());
        assertEquals("(handshake_failed) ejemplo.com.uy:8443: PKIX path building failed", handshake.getMessage());

        Exception tls = HttpsCertificateExpiryAlertTemplate.describe(new SSLException("Unsupported protocol"), target);
        assertEquals(SSLException.class, tls.getClass());
        assertEquals("(tls_error) ejemplo.com.uy:8443: Unsupported protocol", tls.getMessage());

        Exception io = HttpsCertificateExpiryAlertTemplate.describe(new IOException("Broken pipe"), target);
        assertEquals(IOException.class, io.getClass());
        assertEquals("(io_error) ejemplo.com.uy:8443: Broken pipe", io.getMessage());
    }

    @Test
    void keepsTheLabelTheJdkAlreadyProducedAsDetail() {
        Endpoint target = new Endpoint("self-signed.badssl.com", 443);

        Exception described = HttpsCertificateExpiryAlertTemplate.describe(
            new SSLHandshakeException("(certificate_unknown) PKIX path building failed"), target
        );

        assertEquals(SSLHandshakeException.class, described.getClass());
        assertEquals(
            "(handshake_failed) self-signed.badssl.com:443: (certificate_unknown) PKIX path building failed",
            described.getMessage()
        );
    }

    @Test
    void leavesOwnLabelsAndUnrelatedFailuresUntouched() {
        Endpoint target = new Endpoint("ejemplo.com.uy", 443);

        IOException labelled = new IOException("(no_certificate) ejemplo.com.uy:443 did not present an X.509 certificate");
        assertSame(labelled, HttpsCertificateExpiryAlertTemplate.describe(labelled, target));

        IllegalStateException unrelated = new IllegalStateException("boom");
        assertSame(unrelated, HttpsCertificateExpiryAlertTemplate.describe(unrelated, target));
    }

    @Test
    void countsWholeDaysUntilExpiry() {
        Instant now = Instant.parse("2026-09-02T12:00:00Z");

        assertEquals(30, HttpsCertificateExpiryAlertTemplate.daysRemaining(now, now.plus(Duration.ofDays(30))));
        assertEquals(0, HttpsCertificateExpiryAlertTemplate.daysRemaining(now, now.plus(Duration.ofHours(23))));
        assertEquals(-5, HttpsCertificateExpiryAlertTemplate.daysRemaining(now, now.minus(Duration.ofDays(5))));
    }

    private static AlertParameter parameter(String fieldName) throws ReflectiveOperationException {
        Field field = HttpsCertificateExpiryAlertTemplate.class.getDeclaredField(fieldName);
        return field.getAnnotation(AlertParameter.class);
    }
}
