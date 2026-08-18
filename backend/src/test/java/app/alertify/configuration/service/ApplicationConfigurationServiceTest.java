package app.alertify.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.util.LinkedMultiValueMap;

import app.alertify.api.error.ConflictException;
import app.alertify.api.error.InvalidConfigurationValueException;
import app.alertify.configuration.api.ConfigurationCreateRequest;
import app.alertify.configuration.api.ConfigurationUpdateRequest;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.jpa.specification.InvalidFilterException;
import app.alertify.logging.ApplicationEventLogger;
import tools.jackson.databind.node.IntNode;
import tools.jackson.databind.node.StringNode;

@ExtendWith(MockitoExtension.class)
class ApplicationConfigurationServiceTest {

    @Mock private ApplicationConfigurationRepository configurationRepository;
    @Mock private TagRepository tagRepository;
    @Mock private ApplicationConfigurationLookupService lookupService;
    @Mock private ConfigurationCacheInvalidator cacheInvalidator;
    @Mock private ApplicationEventLogger eventLogger;

    @Test
    void doesNotFlushOrChangeVersionWhenUpdateHasNoChanges() {
        ApplicationConfiguration configuration = new ApplicationConfiguration(
            "notification.retry-count", "Number of retries",
            ConfigurationValueType.INTEGER, IntNode.valueOf(5), Set.of()
        );
        when(configurationRepository.findById(10L)).thenReturn(Optional.of(configuration));
        ApplicationConfigurationService service = service();

        var response = service.update(10L, new ConfigurationUpdateRequest(
            0L, "notification.retry-count", "Number of retries",
            ConfigurationValueType.INTEGER, IntNode.valueOf(5), Set.of()
        ));

        assertThat(response.version()).isZero();
        verify(configurationRepository, never()).flush();
        verify(cacheInvalidator, never()).evictAfterCommit(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void flushesWhenValueReallyChanges() {
        ApplicationConfiguration configuration = new ApplicationConfiguration(
            "notification.retry-count", null,
            ConfigurationValueType.INTEGER, IntNode.valueOf(5), Set.of()
        );
        when(configurationRepository.findById(10L)).thenReturn(Optional.of(configuration));
        ApplicationConfigurationService service = service();

        service.update(10L, new ConfigurationUpdateRequest(
            0L, "notification.retry-count", null,
            ConfigurationValueType.INTEGER, IntNode.valueOf(6), Set.of()
        ));

        verify(configurationRepository).flush();
        verify(cacheInvalidator).evictAfterCommit(10L, Set.of("notification.retry-count"));
    }

    @Test
    void rejectsManualCreationOfKeyPart() {
        ApplicationConfigurationService service = service();

        assertThatThrownBy(() -> service.create(new ConfigurationCreateRequest(
            "KEY_PART", null, ConfigurationValueType.STRING,
            StringNode.valueOf("a".repeat(64)), Set.of()
        ))).isInstanceOf(ConflictException.class)
            .hasMessageContaining("created automatically");

        verify(configurationRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsDeletionOfKeyPart() {
        ApplicationConfiguration configuration = new ApplicationConfiguration(
            "KEY_PART", null, ConfigurationValueType.STRING,
            StringNode.valueOf("a".repeat(64)), Set.of()
        );
        when(configurationRepository.findById(1L)).thenReturn(Optional.of(configuration));
        ApplicationConfigurationService service = service();

        assertThatThrownBy(() -> service.delete(1L, 0L))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("cannot be deleted");

        verify(configurationRepository, never()).delete(configuration);
    }

    @Test
    void acceptsAndPreservesSingleSymbolKeyPart() {
        ApplicationConfiguration configuration = new ApplicationConfiguration(
            "KEY_PART", null, ConfigurationValueType.STRING,
            StringNode.valueOf("initial"), Set.of()
        );
        when(configurationRepository.findById(1L)).thenReturn(Optional.of(configuration));
        ApplicationConfigurationService service = service();

        var response = service.update(1L, new ConfigurationUpdateRequest(
            0L, "KEY_PART", null, ConfigurationValueType.STRING,
            StringNode.valueOf("Ñ"), Set.of()
        ));

        assertThat(response.value().stringValue()).isEqualTo("Ñ");
        verify(configurationRepository).flush();
        verify(cacheInvalidator).evictAfterCommit(1L, Set.of("KEY_PART"));
    }

    @Test
    void rejectsEmptyKeyPart() {
        ApplicationConfiguration configuration = new ApplicationConfiguration(
            "KEY_PART", null, ConfigurationValueType.STRING,
            StringNode.valueOf("initial"), Set.of()
        );
        when(configurationRepository.findById(1L)).thenReturn(Optional.of(configuration));
        ApplicationConfigurationService service = service();

        assertThatThrownBy(() -> service.update(1L, new ConfigurationUpdateRequest(
            0L, "KEY_PART", null, ConfigurationValueType.STRING,
            StringNode.valueOf(""), Set.of()
        ))).isInstanceOf(InvalidConfigurationValueException.class)
            .hasMessageContaining("at least one character");
    }

    @Test
    void rejectsUnknownTagOperator() {
        ApplicationConfigurationService service = service();
        var params = new LinkedMultiValueMap<String, String>();
        params.add("tagId", "1");
        params.add("tagOperator", "XOR");

        assertThatThrownBy(() -> service.search(params, PageRequest.of(0, 20)))
            .isInstanceOf(InvalidFilterException.class)
            .hasMessageContaining("tagOperator");
    }

    private ApplicationConfigurationService service() {
        return new ApplicationConfigurationService(
            configurationRepository, tagRepository, new ConfigurationValueValidator(),
            lookupService, cacheInvalidator, eventLogger
        );
    }
}
