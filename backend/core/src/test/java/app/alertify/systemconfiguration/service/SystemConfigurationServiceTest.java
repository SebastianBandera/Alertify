package app.alertify.systemconfiguration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.StringNode;

import app.alertify.api.error.ConflictException;
import app.alertify.api.error.InvalidConfigurationValueException;
import app.alertify.jpa.entity.SystemConfiguration;
import app.alertify.jpa.repository.SystemConfigurationRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.systemconfiguration.api.SystemConfigurationRegenerateRequest;
import app.alertify.systemconfiguration.api.SystemConfigurationUpdateRequest;

@ExtendWith(MockitoExtension.class)
class SystemConfigurationServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Mock private SystemConfigurationRepository repository;
    @Mock private ApplicationEventLogger eventLogger;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    private SystemConfigurationService service;

    @BeforeEach
    void setUp() {
        service = new SystemConfigurationService(repository, eventLogger, applicationEventPublisher);
    }

    @Test
    void regenerateReplacesTheValueWhenHidden() {
        SystemConfiguration configuration = new SystemConfiguration("KEY_PART", StringNode.valueOf("initial"), true);
        when(repository.findById(1L)).thenReturn(Optional.of(configuration));

        service.regenerate(1L, new SystemConfigurationRegenerateRequest(0L));

        assertThat(configuration.getValue().stringValue()).isNotEqualTo("initial");
        assertThat(configuration.getValue().stringValue()).hasSize(64);
    }

    @Test
    void regenerateRejectsEntriesWithAVisibleValue() {
        SystemConfiguration configuration = new SystemConfiguration(
                "CRON_QUIET_HOURS", StringNode.valueOf("untouched"), false
        );
        when(repository.findById(2L)).thenReturn(Optional.of(configuration));

        assertThatThrownBy(() -> service.regenerate(2L, new SystemConfigurationRegenerateRequest(0L)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("does not support regeneration");

        assertThat(configuration.getValue().stringValue()).isEqualTo("untouched");
        verify(repository, never()).flush();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"days\":0}", "{\"days\":-3}", "{\"days\":7.5}", "{\"days\":\"10\"}", "{\"days\":\"abc\"}",
        "{\"days\":366}", "{}", "10", "\"10\""
    })
    void updateRejectsADashboardWindowThatIsNotAPositiveWholeNumberOfDays(String json) {
        SystemConfiguration configuration = new SystemConfiguration("DASHBOARD_HISTORY_WINDOW", JSON.readTree("{\"days\":10}"), false);
        when(repository.findById(4L)).thenReturn(Optional.of(configuration));

        assertThatThrownBy(() -> service.update(4L, new SystemConfigurationUpdateRequest(0L, JSON.readTree(json))))
                .isInstanceOf(InvalidConfigurationValueException.class)
                .hasMessageContaining("DASHBOARD_HISTORY_WINDOW");

        assertThat(configuration.getValue().get("days").intValue()).isEqualTo(10);
        verify(repository, never()).flush();
    }

    @Test
    void updateAcceptsAPositiveDashboardWindow() {
        SystemConfiguration configuration = new SystemConfiguration("DASHBOARD_HISTORY_WINDOW", JSON.readTree("{\"days\":10}"), false);
        when(repository.findById(5L)).thenReturn(Optional.of(configuration));

        service.update(5L, new SystemConfigurationUpdateRequest(0L, JSON.readTree("{\"days\":7}")));

        assertThat(configuration.getValue().get("days").intValue()).isEqualTo(7);
    }

    @Test
    void regenerateChecksVersionBeforeCheckingVisibility() {
        SystemConfiguration configuration = new SystemConfiguration(
                "CRON_QUIET_HOURS", StringNode.valueOf("untouched"), false
        );
        when(repository.findById(3L)).thenReturn(Optional.of(configuration));

        assertThatThrownBy(() -> service.regenerate(3L, new SystemConfigurationRegenerateRequest(99L)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("modified by another request");
    }
}
