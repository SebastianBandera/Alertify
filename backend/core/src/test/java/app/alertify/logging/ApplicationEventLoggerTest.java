package app.alertify.logging;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ApplicationEventLoggerTest {

    @Mock private ApplicationLogWriter writer;

    @AfterEach
    void clearSynchronization() {
        MDC.clear();
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void capturesTheRequestPathFromTheLoggingContext() {
        MDC.put(ApplicationEventLogger.REQUEST_PATH_MDC_KEY, "/api/configurations/7");
        ApplicationEventLogger logger = new ApplicationEventLogger(writer, "test-app");

        logger.success("CONFIGURATION_VIEWED", Map.of("id", 7));

        ArgumentCaptor<ApplicationLogCommand> command =
            ArgumentCaptor.forClass(ApplicationLogCommand.class);
        verify(writer).persist(command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue().path())
            .isEqualTo("/api/configurations/7");
    }

    @Test
    void persistsOnlyAfterTheBusinessTransactionCommits() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        ApplicationEventLogger logger = new ApplicationEventLogger(writer, "test-app");

        logger.successAfterCommit("CONFIGURATION_UPDATED", Map.of("id", 7));

        verify(writer, never()).persist(org.mockito.ArgumentMatchers.any());
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(TransactionSynchronization::afterCommit);

        ArgumentCaptor<ApplicationLogCommand> command = ArgumentCaptor.forClass(ApplicationLogCommand.class);
        verify(writer).persist(command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue().event())
            .isEqualTo("CONFIGURATION_UPDATED");
        org.assertj.core.api.Assertions.assertThat(command.getValue().actor().username())
            .isEqualTo("system");
    }
    @Test
    void explicitWebSocketIdentityAndRequestContextReachBothConsoleAndPersistence() {
        ApplicationEventLogger logger = new ApplicationEventLogger(writer, "test-app");
        Jwt jwt = Jwt.withTokenValue("test-only-token").header("alg", "RS256").subject("subject-123").claim("preferred_username", "alice").build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        RequestLogContext request = new RequestLogContext(UUID.randomUUID(), "/alertify/api/admin/events", "GET", System.nanoTime());
        MDC.put(ApplicationEventLogger.REQUEST_PATH_MDC_KEY, "/unrelated");
        Logger console = (Logger) LoggerFactory.getLogger(ApplicationEventLogger.class);
        ListAppender<ILoggingEvent> messages = new ListAppender<>();
        messages.start();
        console.addAppender(messages);
        try {
            logger.success("API_REQUEST", request.data(101), authentication, request);

            ArgumentCaptor<ApplicationLogCommand> command = ArgumentCaptor.forClass(ApplicationLogCommand.class);
            verify(writer).persist(command.capture());
            org.assertj.core.api.Assertions.assertThat(command.getValue().actor()).isEqualTo(new LogActor("subject-123", "alice"));
            org.assertj.core.api.Assertions.assertThat(command.getValue().requestId()).isEqualTo(request.requestId());
            org.assertj.core.api.Assertions.assertThat(command.getValue().path()).isEqualTo(request.path());
            org.assertj.core.api.Assertions.assertThat(messages.list).singleElement().satisfies(entry ->
                    org.assertj.core.api.Assertions.assertThat(entry.getFormattedMessage()).contains("user=alice subject=subject-123", "requestId=" + request.requestId(), "path=" + request.path()).doesNotContain("test-only-token"));
            org.assertj.core.api.Assertions.assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        } finally {
            console.detachAppender(messages);
            messages.stop();
        }
    }

    @Test
    void unauthenticatedWebSocketFailuresUseAnAnonymousActor() {
        ApplicationEventLogger logger = new ApplicationEventLogger(writer, "test-app");
        RequestLogContext request = new RequestLogContext(UUID.randomUUID(), "/api/viewer/events", "GET", System.nanoTime());

        logger.failure("WEBSOCKET_AUTHENTICATION_FAILED", Map.of("reason", "TOKEN_INVALID"), null, request);

        ArgumentCaptor<ApplicationLogCommand> command = ArgumentCaptor.forClass(ApplicationLogCommand.class);
        verify(writer).persist(command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue().actor()).isEqualTo(new LogActor("anonymous", "anonymous"));
        org.assertj.core.api.Assertions.assertThat(command.getValue().outcome()).isEqualTo(ApplicationLogOutcome.FAILURE);
    }

    @Test
    void canPersistExpectedBusinessFailureAtInfoLevel() {
        ApplicationEventLogger logger = new ApplicationEventLogger(writer, "test-app");

        logger.failure("API_ERROR_SHOWN", ApplicationLogLevel.INFO, Map.of("errorCode", "CONFIGURATION_TAG_IN_USE"));

        ArgumentCaptor<ApplicationLogCommand> command =
            ArgumentCaptor.forClass(ApplicationLogCommand.class);
        verify(writer).persist(command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue().level())
            .isEqualTo(ApplicationLogLevel.INFO);
        org.assertj.core.api.Assertions.assertThat(command.getValue().outcome())
            .isEqualTo(ApplicationLogOutcome.FAILURE);
    }

}
