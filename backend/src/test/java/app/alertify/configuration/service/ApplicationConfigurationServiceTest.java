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
import app.alertify.configuration.api.ConfigurationCreateRequest;
import app.alertify.configuration.api.ConfigurationUpdateRequest;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.jpa.specification.InvalidFilterException;
import tools.jackson.databind.node.IntNode;
import tools.jackson.databind.node.StringNode;

@ExtendWith(MockitoExtension.class)
class ApplicationConfigurationServiceTest {

    @Mock private ApplicationConfigurationRepository configurationRepository;
    @Mock private TagRepository tagRepository;

    @Test
    void doesNotFlushOrChangeVersionWhenUpdateHasNoChanges() {
        ApplicationConfiguration configuration = new ApplicationConfiguration(
            "notification.retry-count", "Number of retries",
            ConfigurationValueType.INTEGER, IntNode.valueOf(5), Set.of()
        );
        when(configurationRepository.findById(10L)).thenReturn(Optional.of(configuration));
        ApplicationConfigurationService service = new ApplicationConfigurationService(
            configurationRepository, tagRepository, new ConfigurationValueValidator()
        );

        var response = service.update(10L, new ConfigurationUpdateRequest(
            0L, "notification.retry-count", "Number of retries",
            ConfigurationValueType.INTEGER, IntNode.valueOf(5), Set.of()
        ));

        assertThat(response.version()).isZero();
        verify(configurationRepository, never()).flush();
    }

    @Test
    void flushesWhenValueReallyChanges() {
        ApplicationConfiguration configuration = new ApplicationConfiguration(
            "notification.retry-count", null,
            ConfigurationValueType.INTEGER, IntNode.valueOf(5), Set.of()
        );
        when(configurationRepository.findById(10L)).thenReturn(Optional.of(configuration));
        ApplicationConfigurationService service = new ApplicationConfigurationService(
            configurationRepository, tagRepository, new ConfigurationValueValidator()
        );

        service.update(10L, new ConfigurationUpdateRequest(
            0L, "notification.retry-count", null,
            ConfigurationValueType.INTEGER, IntNode.valueOf(6), Set.of()
        ));

        verify(configurationRepository).flush();
    }

    @Test
    void rejectsManualCreationOfKeyPart() {
        ApplicationConfigurationService service = new ApplicationConfigurationService(
            configurationRepository, tagRepository, new ConfigurationValueValidator()
        );

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
        ApplicationConfigurationService service = new ApplicationConfigurationService(
            configurationRepository, tagRepository, new ConfigurationValueValidator()
        );

        assertThatThrownBy(() -> service.delete(1L, 0L))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("cannot be deleted");

        verify(configurationRepository, never()).delete(configuration);
    }

    @Test
    void rejectsUnknownTagOperator() {
        ApplicationConfigurationService service = new ApplicationConfigurationService(
            configurationRepository, tagRepository, new ConfigurationValueValidator()
        );
        var params = new LinkedMultiValueMap<String, String>();
        params.add("tagId", "1");
        params.add("tagOperator", "XOR");

        assertThatThrownBy(() -> service.search(params, PageRequest.of(0, 20)))
            .isInstanceOf(InvalidFilterException.class)
            .hasMessageContaining("tagOperator");
    }
}
