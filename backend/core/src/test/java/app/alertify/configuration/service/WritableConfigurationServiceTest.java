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
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.entity.ConfigurationBinaryValue;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ConfigurationBinaryValueRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.worker.contract.BinaryPayloadCodec;
import app.alertify.worker.grpc.WritableConfigurationValue;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.IntNode;

@ExtendWith(MockitoExtension.class)
class WritableConfigurationServiceTest {

    @Mock private ApplicationConfigurationRepository configurationRepository;
    @Mock private ConfigurationExpressionService expressionService;
    @Mock private ConfigurationCacheInvalidator cacheInvalidator;
    @Mock private ApplicationEventLogger eventLogger;
    @Mock private ConfigurationBinaryValueRepository binaryRepository;

    @Test
    void persistsChangedValueAndLogsTheAlertThatOverwroteIt() {
        ApplicationConfiguration configuration = configuration(true);
        when(configurationRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(configuration));
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
        when(configurationRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(configuration));

        service().apply(20L, "Counter alert", UUID.randomUUID(), Set.of(result("6")));

        assertThat(configuration.getValue().intValue()).isEqualTo(5);
        verify(configurationRepository, never()).flush();
        verify(expressionService, never()).synchronizeDependencies(configuration);
        verify(eventLogger, never()).successAfterCommit(eq("CONFIGURATION_OVERWRITTEN_BY_ALERT"), anyMap());
    }

    @Test
    void rejectsWriteBackWhenTheConfigurationChangedAfterPreparation() {
        ApplicationConfiguration configuration = configuration(true);
        ReflectionTestUtils.setField(configuration, "version", 2L);
        when(configurationRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(configuration));
        WritableConfigurationValue stale = result("6").toBuilder().setExpectedVersion(1L).build();

        service().apply(20L, "Counter alert", UUID.randomUUID(), Set.of(stale));

        assertThat(configuration.getValue().intValue()).isEqualTo(5);
        verify(configurationRepository, never()).flush();
        verify(eventLogger).errorAfterCommit(
                eq("CONFIGURATION_OVERWRITE_REJECTED"),
                org.mockito.ArgumentMatchers.argThat(data -> data.get("reason").toString().contains("changed after execution preparation"))
        );
    }

    @Test
    void recompressesAndPersistsWritableBinaryBytes() {
        byte[] changed = new byte[] { 0, 1, 2, 3, 4, (byte) 255 };
        ApplicationConfiguration configuration = new ApplicationConfiguration(
                "database", null, ConfigurationValueType.BINARY,
                tools.jackson.databind.node.JsonNodeFactory.instance.objectNode(), Set.of(), true);
        configuration.changeBinaryMetadata("data.sqlite", "application/vnd.sqlite3", 1, 1, new byte[32]);
        ReflectionTestUtils.setField(configuration, "id", 10L);
        when(configurationRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(configuration));

        WritableConfigurationValue value = WritableConfigurationValue.newBuilder()
                .setConfigurationId(10L).setParameterName("database")
                .setBinaryValue(com.google.protobuf.ByteString.copyFrom(BinaryPayloadCodec.compress(changed, 104857600)))
                .build();
        service().apply(20L, "SQLite updater", UUID.randomUUID(), Set.of(value));

        ArgumentCaptor<ConfigurationBinaryValue> persisted = ArgumentCaptor.forClass(ConfigurationBinaryValue.class);
        verify(binaryRepository).save(persisted.capture());
        assertThat(BinaryPayloadCodec.decompress(persisted.getValue().getZipValue(), 104857600)).isEqualTo(changed);
        assertThat(configuration.getBinarySize()).isEqualTo(changed.length);
        verify(configurationRepository).flush();
    }

    @Test
    void persistsEmptyWritableBinaryBytes() {
        ApplicationConfiguration configuration = new ApplicationConfiguration(
                "database", null, ConfigurationValueType.BINARY,
                tools.jackson.databind.node.JsonNodeFactory.instance.objectNode(), Set.of(), true);
        configuration.changeBinaryMetadata("data.sqlite", "application/vnd.sqlite3", 6, 1, new byte[32]);
        ReflectionTestUtils.setField(configuration, "id", 10L);
        when(configurationRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(configuration));

        WritableConfigurationValue value = WritableConfigurationValue.newBuilder()
                .setConfigurationId(10L).setParameterName("database")
                .setBinaryValue(com.google.protobuf.ByteString.copyFrom(BinaryPayloadCodec.compress(new byte[0], 104857600)))
                .build();
        service().apply(20L, "SQLite updater", UUID.randomUUID(), Set.of(value));

        ArgumentCaptor<ConfigurationBinaryValue> persisted = ArgumentCaptor.forClass(ConfigurationBinaryValue.class);
        verify(binaryRepository).save(persisted.capture());
        assertThat(BinaryPayloadCodec.decompress(persisted.getValue().getZipValue(), 104857600)).isEmpty();
        assertThat(configuration.getBinarySize()).isZero();
        assertThat(configuration.getBinaryZipSize()).isPositive();
        verify(configurationRepository).flush();
        verify(eventLogger, never()).errorAfterCommit(eq("CONFIGURATION_OVERWRITE_REJECTED"), anyMap());
    }

    private WritableConfigurationService service() {
        return new WritableConfigurationService(
                configurationRepository, new ConfigurationValueValidator(), expressionService,
                cacheInvalidator, eventLogger, JsonMapper.builder().build(),
                binaryRepository,
                new app.alertify.binary.BinaryPayloadService(104857600)
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
