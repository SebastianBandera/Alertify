package app.alertify.logging;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.slf4j.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
            .forEach(synchronization -> synchronization.afterCommit());

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

}
