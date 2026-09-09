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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.node.StringNode;

import app.alertify.api.error.ConflictException;
import app.alertify.jpa.entity.SystemConfiguration;
import app.alertify.jpa.repository.SystemConfigurationRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.systemconfiguration.api.SystemConfigurationRegenerateRequest;

@ExtendWith(MockitoExtension.class)
class SystemConfigurationServiceTest {

    @Mock private SystemConfigurationRepository repository;
    @Mock private ApplicationEventLogger eventLogger;

    private SystemConfigurationService service;

    @BeforeEach
    void setUp() {
        service = new SystemConfigurationService(repository, eventLogger);
    }

    @Test
    void regenerateReplacesTheValueWhenHidden() {
        SystemConfiguration configuration = new SystemConfiguration("KEY_PART", null, StringNode.valueOf("initial"), true);
        when(repository.findById(1L)).thenReturn(Optional.of(configuration));

        service.regenerate(1L, new SystemConfigurationRegenerateRequest(0L));

        assertThat(configuration.getValue().stringValue()).isNotEqualTo("initial");
        assertThat(configuration.getValue().stringValue()).hasSize(64);
    }

    @Test
    void regenerateRejectsEntriesWithAVisibleValue() {
        SystemConfiguration configuration = new SystemConfiguration(
                "CRON_QUIET_HOURS", null, StringNode.valueOf("untouched"), false
        );
        when(repository.findById(2L)).thenReturn(Optional.of(configuration));

        assertThatThrownBy(() -> service.regenerate(2L, new SystemConfigurationRegenerateRequest(0L)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("does not support regeneration");

        assertThat(configuration.getValue().stringValue()).isEqualTo("untouched");
        verify(repository, never()).flush();
    }

    @Test
    void regenerateChecksVersionBeforeCheckingVisibility() {
        SystemConfiguration configuration = new SystemConfiguration(
                "CRON_QUIET_HOURS", null, StringNode.valueOf("untouched"), false
        );
        when(repository.findById(3L)).thenReturn(Optional.of(configuration));

        assertThatThrownBy(() -> service.regenerate(3L, new SystemConfigurationRegenerateRequest(99L)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("modified by another request");
    }
}
