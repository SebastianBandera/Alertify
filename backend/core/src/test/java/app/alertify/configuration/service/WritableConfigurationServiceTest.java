package app.alertify.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.worker.grpc.WritableConfigurationValue;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.IntNode;

@ExtendWith(MockitoExtension.class)
class WritableConfigurationServiceTest {

    @Mock private ApplicationConfigurationRepository configurationRepository;
    @Mock private ConfigurationExpressionService expressionService;
    @Mock private ConfigurationCacheInvalidator cacheInvalidator;
    @Mock private ApplicationEventLogger eventLogger;

    @Test
    void persistsChangedValueAndLogsTheAlertThatOverwroteIt() {
        ApplicationConfiguration configuration = configuration(true);
        when(configurationRepository.findById(10L)).thenReturn(Optional.of(configuration));
        WritableConfigurationService service = service();
        UUID executionId = UUID.randomUUID();

        service.apply(20L, "Counter alert", executionId, Set.of(result("6")));

        assertThat(configuration.getValue().intValue()).isEqualTo(6);
        verify(expressionService).synchronizeDependencies(configuration);
        verify(configurationRepository).flush();
        verify(cacheInvalidator).evictAfterCommit(10L, Set.of("counter"));
        verify(eventLogger).successAfterCommit(
                eq("CONFIGURATION_OVERWRITTEN_BY_ALERT"),
                org.mockito.ArgumentMatchers.argThat(data ->
                    data.get("configurationName").equals("counter")
                        && data.get("alertName").equals("Counter alert")
                        && data.get("executionId").equals(executionId)
                        && data.get("parameterName").equals("counter")
                )
        );
    }

    @Test
    void ignoresWorkerValueWhenConfigurationIsNoLongerWritable() {
        ApplicationConfiguration configuration = configuration(false);
        when(configurationRepository.findById(10L)).thenReturn(Optional.of(configuration));

        service().apply(20L, "Counter alert", UUID.randomUUID(), Set.of(result("6")));

        assertThat(configuration.getValue().intValue()).isEqualTo(5);
        verify(configurationRepository, never()).flush();
        verify(expressionService, never()).synchronizeDependencies(configuration);
        verify(eventLogger, never()).successAfterCommit(eq("CONFIGURATION_OVERWRITTEN_BY_ALERT"), anyMap());
    }

    private WritableConfigurationService service() {
        return new WritableConfigurationService(
                configurationRepository, new ConfigurationValueValidator(), expressionService,
                cacheInvalidator, eventLogger, JsonMapper.builder().build()
        );
    }

    private static ApplicationConfiguration configuration(boolean writable) {
        ApplicationConfiguration configuration = new ApplicationConfiguration(
                "counter", null, ConfigurationValueType.INTEGER, IntNode.valueOf(5), Set.of(), writable
        );
        ReflectionTestUtils.setField(configuration, "id", 10L);
        return configuration;
    }

    private static WritableConfigurationValue result(String value) {
        return WritableConfigurationValue.newBuilder()
                .setConfigurationId(10L)
                .setParameterName("counter")
                .setValue(value)
                .build();
    }
}
