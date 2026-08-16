package app.alertify.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import app.alertify.configuration.api.ConfigurationUpdateRequest;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.TagRepository;
import tools.jackson.databind.node.IntNode;

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
}
