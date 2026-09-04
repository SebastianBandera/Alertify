package app.alertify.alerts.templates;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateTag;

/**
 * Standard alert that warns before the HTTPS certificate served by a site
 * expires. Its stable alternate key is this class' fully qualified name.
 */
@AlertTemplate(
    nameKey = "alerts.template.httpsCertificate.name",
    descriptionKey = "alerts.template.httpsCertificate.description",
    tags = {
        @AlertTemplateTag(nameKey = "alerts.templateTag.network", color = "#0EA5E9"),
        @AlertTemplateTag(nameKey = "alerts.templateTag.security", color = "#7C3AED")
    },
    sourcePath = "app/alertify/alerts/templates/HttpsCertificateExpiryAlertTemplate.java"
)
public final class HttpsCertificateExpiryAlertTemplate implements AlertEvaluator {

    private static final int DEFAULT_PORT = 443;

    private static final List<String> FAILURE_REASONS = List.of(
        "no_certificate", "unknown_host", "timeout", "connect_failed", "handshake_failed", "tls_error", "io_error"
    );

    @AlertParameter(
        labelKey = "alerts.template.httpsCertificate.endpoint",
        descriptionKey = "alerts.template.httpsCertificate.endpointDescription",
        bindingAllowed = true,
        order = 1
    )
    private final String endpoint;

    @AlertParameter(
        labelKey = "alerts.template.httpsCertificate.warningDays",
        descriptionKey = "alerts.template.httpsCertificate.warningDaysDescription",
        options = { "7", "14", "30", "60", "90" },
        bindingAllowed = true,
        defaultValue = "30",
        order = 2
    )
    private final int warningDays;

    @AlertParameter(
        labelKey = "alerts.template.httpsCertificate.timeout",
        descriptionKey = "alerts.template.httpsCertificate.timeoutDescription",
        options = { "3", "5", "10", "30" },
        bindingAllowed = true,
        defaultValue = "10",
        order = 3
    )
    private final int timeoutSeconds;

    @AlertParameter(
        labelKey = "alerts.template.httpsCertificate.verifyHostname",
        descriptionKey = "alerts.template.httpsCertificate.verifyHostnameDescription",
        options = { "false", "true" },
        bindingAllowed = false,
        defaultValue = "true",
        order = 4
    )
    private final boolean verifyHostname;

    public HttpsCertificateExpiryAlertTemplate(String endpoint, int warningDays, int timeoutSeconds, boolean verifyHostname) {
        if (warningDays < 0)
            throw new IllegalArgumentException("warningDays must not be negative");

        if (timeoutSeconds <= 0)
            throw new IllegalArgumentException("timeoutSeconds must be positive");

        this.endpoint = endpoint;
        this.warningDays = warningDays;
        this.timeoutSeconds = timeoutSeconds;
        this.verifyHostname = verifyHostname;
    }

    @Override
    public AlertResult evaluate(AlertExecutionContext context) throws Exception {
        Endpoint target = parseEndpoint(endpoint);
        long startedNanos = System.nanoTime();
        CertificateSummary certificate = readCertificate(target);
        long handshakeMs = elapsedMillis(startedNanos);

        Instant now = Instant.now();
        long daysRemaining = daysRemaining(now, certificate.notAfter());
        boolean expired = now.isAfter(certificate.notAfter());
        boolean notYetValid = now.isBefore(certificate.notBefore());
        boolean hostnameMatch = hostnameMatches(target.host(), certificate.dnsNames(), certificate.commonName());

        Map<String, Object> statusMessage = new LinkedHashMap<>();
        statusMessage.put("endpoint", endpoint);
        statusMessage.put("host", target.host());
        statusMessage.put("port", target.port());
        statusMessage.put("subject", certificate.subject());
        statusMessage.put("issuer", certificate.issuer());
        statusMessage.put("serialNumber", certificate.serialNumber());
        statusMessage.put("notBefore", certificate.notBefore().toString());
        statusMessage.put("notAfter", certificate.notAfter().toString());
        statusMessage.put("daysRemaining", daysRemaining);
        statusMessage.put("warningDays", warningDays);
        statusMessage.put("expired", expired);
        if (notYetValid)
            statusMessage.put("notYetValid", true);

        if (verifyHostname)
            statusMessage.put("hostnameMatch", hostnameMatch);

        statusMessage.put("handshakeMs", handshakeMs);
        statusMessage.put("checkedAt", now.toString());

        context.setState(
            "host=" + target.host() + ";port=" + target.port()
                + ";notAfter=" + certificate.notAfter()
                + ";daysRemaining=" + daysRemaining
                + ";expired=" + expired
        );

        if (expired || notYetValid || daysRemaining <= warningDays)
            return AlertResult.warn(statusMessage);

        if (verifyHostname && !hostnameMatch)
            return AlertResult.warn(statusMessage);

        return AlertResult.success(statusMessage);
    }

    private CertificateSummary readCertificate(Endpoint target) throws Exception {
        try {
            return handshake(target);
        } catch (Exception exception) {
            throw describe(exception, target);
        }
    }

    private CertificateSummary handshake(Endpoint target) throws Exception {
        int timeoutMillis = Math.multiplyExact(timeoutSeconds, 1_000);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[] { new ExpiryTolerantTrustManager(defaultTrustManager()) }, null);

        try (SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(target.host(), target.port()), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);

            SSLParameters parameters = socket.getSSLParameters();
            // Hostname mismatches are reported as findings, not as handshake errors.
            parameters.setEndpointIdentificationAlgorithm(null);
            if (!isIpAddress(target.host()))
                parameters.setServerNames(List.of(new SNIHostName(target.host())));

            socket.setSSLParameters(parameters);
            socket.startHandshake();

            SSLSession session = socket.getSession();
            Certificate[] chain = session.getPeerCertificates();
            if (chain.length == 0 || !(chain[0] instanceof X509Certificate leaf))
                throw new IOException(label("no_certificate", target) + " did not present an X.509 certificate");

            return summarize(leaf);
        }
    }

    /**
     * Rebuilds a connection failure as "(case) host:port: detail". The original
     * exception class is preserved so that the reported error type stays exact,
     * the original instance is kept as the cause, and any label the JDK already
     * produced (such as a TLS alert name) is retained as the detail.
     */
    static Exception describe(Exception exception, Endpoint target) {
        String message = exception.getMessage();
        if (message != null && isLabelled(message))
            return exception;

        String detail = message == null || message.isBlank() ? "" : ": " + message;

        if (exception instanceof UnknownHostException)
            return causedBy(new UnknownHostException(label("unknown_host", target) + " could not be resolved"), exception);

        if (exception instanceof SocketTimeoutException)
            return causedBy(new SocketTimeoutException(label("timeout", target) + " timed out during the TLS handshake"), exception);

        if (exception instanceof ConnectException)
            return causedBy(new ConnectException(label("connect_failed", target) + " refused the connection" + detail), exception);

        if (exception instanceof SSLHandshakeException)
            return causedBy(new SSLHandshakeException(label("handshake_failed", target) + detail), exception);

        if (exception instanceof SSLException)
            return causedBy(new SSLException(label("tls_error", target) + detail), exception);

        if (exception instanceof IOException)
            return causedBy(new IOException(label("io_error", target) + detail), exception);

        return exception;
    }

    private static boolean isLabelled(String message) {
        for (String reason : FAILURE_REASONS) {
            if (message.startsWith("(" + reason + ")"))
                return true;
        }
        return false;
    }

    private static String label(String reason, Endpoint target) {
        return "(" + reason + ") " + target.host() + ":" + target.port();
    }

    private static <T extends Throwable> T causedBy(T exception, Throwable cause) {
        exception.initCause(cause);
        return exception;
    }

    private static CertificateSummary summarize(X509Certificate certificate) throws CertificateException {
        return new CertificateSummary(
            certificate.getSubjectX500Principal().getName(),
            certificate.getIssuerX500Principal().getName(),
            certificate.getSerialNumber().toString(16),
            certificate.getNotBefore().toInstant(),
            certificate.getNotAfter().toInstant(),
            subjectAlternativeDnsNames(certificate),
            commonName(certificate.getSubjectX500Principal().getName())
        );
    }

    private static List<String> subjectAlternativeDnsNames(X509Certificate certificate) throws CertificateException {
        var names = certificate.getSubjectAlternativeNames();
        if (names == null)
            return List.of();

        List<String> dnsNames = new ArrayList<>();
        for (List<?> name : names) {
            if (name.size() >= 2 && Integer.valueOf(2).equals(name.get(0)) && name.get(1) instanceof String value)
                dnsNames.add(value);
        }
        return List.copyOf(dnsNames);
    }

    private static X509ExtendedTrustManager defaultTrustManager() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509ExtendedTrustManager trustManager)
                return trustManager;
        }
        throw new IllegalStateException("The platform does not provide an X509ExtendedTrustManager");
    }

    static String commonName(String distinguishedName) {
        if (distinguishedName == null)
            return "";

        for (String part : distinguishedName.split(",")) {
            String trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, "CN=", 0, 3))
                return trimmed.substring(3).trim();
        }
        return "";
    }

    static boolean hostnameMatches(String host, List<String> dnsNames, String commonName) {
        List<String> candidates = dnsNames.isEmpty() && !commonName.isEmpty()
            ? List.of(commonName)
            : dnsNames;

        String normalizedHost = host.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (matches(normalizedHost, candidate.trim().toLowerCase(Locale.ROOT)))
                return true;
        }
        return false;
    }

    private static boolean matches(String host, String candidate) {
        if (candidate.isEmpty())
            return false;

        if (!candidate.startsWith("*."))
            return host.equals(candidate);

        String suffix = candidate.substring(1);
        if (!host.endsWith(suffix))
            return false;

        String label = host.substring(0, host.length() - suffix.length());
        return !label.isEmpty() && label.indexOf('.') < 0;
    }

    static long daysRemaining(Instant now, Instant notAfter) {
        return ChronoUnit.DAYS.between(now, notAfter);
    }

    static Endpoint parseEndpoint(String configured) {
        if (configured == null || configured.isBlank())
            throw new IllegalArgumentException("endpoint must not be blank");

        String value = configured.trim();
        if (value.contains("://")) {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host == null || host.isBlank())
                throw new IllegalArgumentException("endpoint does not declare a host: " + configured);

            return new Endpoint(host, uri.getPort() > 0 ? uri.getPort() : DEFAULT_PORT);
        }

        String authority = value;
        int pathIndex = authority.indexOf('/');
        if (pathIndex >= 0)
            authority = authority.substring(0, pathIndex);

        int portIndex = authority.lastIndexOf(':');
        if (portIndex < 0)
            return new Endpoint(requireHost(authority, configured), DEFAULT_PORT);

        String host = requireHost(authority.substring(0, portIndex), configured);
        try {
            int port = Integer.parseInt(authority.substring(portIndex + 1));
            if (port <= 0 || port > 65535)
                throw new IllegalArgumentException("endpoint declares an invalid port: " + configured);

            return new Endpoint(host, port);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("endpoint declares an invalid port: " + configured, exception);
        }
    }

    private static String requireHost(String host, String configured) {
        if (host.isBlank())
            throw new IllegalArgumentException("endpoint does not declare a host: " + configured);

        return host;
    }

    private static boolean isIpAddress(String host) {
        return host.indexOf(':') >= 0 || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    record Endpoint(String host, int port) {
    }

    private record CertificateSummary(
        String subject,
        String issuer,
        String serialNumber,
        Instant notBefore,
        Instant notAfter,
        List<String> dnsNames,
        String commonName
    ) {
    }

    /**
     * Delegates trust validation to the platform trust manager but tolerates
     * validity failures, so that an expired certificate is reported as a warning
     * instead of aborting the handshake. No application data is exchanged over
     * the socket beyond the handshake itself.
     */
    private static final class ExpiryTolerantTrustManager extends X509ExtendedTrustManager {

        private final X509ExtendedTrustManager delegate;

        private ExpiryTolerantTrustManager(X509ExtendedTrustManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            tolerateValidityFailures(() -> delegate.checkServerTrusted(chain, authType, socket));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            tolerateValidityFailures(() -> delegate.checkServerTrusted(chain, authType, engine));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            tolerateValidityFailures(() -> delegate.checkServerTrusted(chain, authType));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            delegate.checkClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            delegate.checkClientTrusted(chain, authType, engine);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }

        private static void tolerateValidityFailures(TrustCheck check) throws CertificateException {
            try {
                check.run();
            } catch (CertificateException exception) {
                if (!causedByValidity(exception))
                    throw exception;
            }
        }

        private static boolean causedByValidity(Throwable throwable) {
            for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
                if (cause instanceof CertificateExpiredException || cause instanceof CertificateNotYetValidException)
                    return true;
            }
            return false;
        }

        @FunctionalInterface
        private interface TrustCheck {

            void run() throws CertificateException;
        }
    }
}
