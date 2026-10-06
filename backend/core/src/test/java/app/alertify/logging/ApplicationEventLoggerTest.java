package app.alertify.logging;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import app.alertify.ai.AiInvocationContext;
import app.alertify.ai.AiInvocationContextHolder;

@ExtendWith(MockitoExtension.class)
class ApplicationEventLoggerTest {

    @Mock private ApplicationLogWriter writer;

    @AfterEach
    void clearSynchronization() {
        MDC.clear();
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

    @Test
    void capturesAiProvenanceInTheImmutableLogCommand() {
        ApplicationEventLogger logger = new ApplicationEventLogger(writer, "test-app");
        AiInvocationContext context = new AiInvocationContext("subject", "admin", Set.of("ROLE_ADMIN"), 72L, UUID.randomUUID());

        AiInvocationContextHolder.runWith(context, () -> logger.success("CONFIGURATION_UPDATED", Map.of("id", 7)));

        ArgumentCaptor<ApplicationLogCommand> command = ArgumentCaptor.forClass(ApplicationLogCommand.class);
        verify(writer).persist(command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue().aiProvenance().assisted()).isTrue();
        org.assertj.core.api.Assertions.assertThat(command.getValue().aiProvenance().conversationId()).isEqualTo(72L);
        org.assertj.core.api.Assertions.assertThat(command.getValue().actor().subject()).isEqualTo("subject");
        org.assertj.core.api.Assertions.assertThat(command.getValue().actor().username()).isEqualTo("admin");
    }

}
