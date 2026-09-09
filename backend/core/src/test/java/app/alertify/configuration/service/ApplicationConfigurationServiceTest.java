package app.alertify.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.LinkedMultiValueMap;

import app.alertify.configuration.api.ConfigurationUpdateRequest;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.jpa.specification.InvalidFilterException;
import app.alertify.logging.ApplicationEventLogger;
import tools.jackson.databind.node.IntNode;

@ExtendWith(MockitoExtension.class)
class ApplicationConfigurationServiceTest {

    @Mock private ApplicationConfigurationRepository configurationRepository;
    @Mock private TagRepository tagRepository;
    @Mock private ApplicationConfigurationLookupService lookupService;
    @Mock private ConfigurationCacheInvalidator cacheInvalidator;
    @Mock private ConfigurationCsvCodec csvCodec;
    @Mock private ConfigurationExpressionService expressionService;
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
    void exportIncludesEveryConfiguration() {
        ApplicationConfiguration configuration = new ApplicationConfiguration(
            "notification.url", null, ConfigurationValueType.STRING,
            tools.jackson.databind.node.StringNode.valueOf("https://example.test"), Set.of()
        );
        when(configurationRepository.findAll(any(Sort.class))).thenReturn(List.of(configuration));
        when(csvCodec.write(any())).thenReturn(new byte[] { 1 });
        ApplicationConfigurationService service = service();

        service.exportCsv();

        verify(csvCodec).write(argThat(configurations ->
            configurations.size() == 1 && configurations.getFirst() == configuration
        ));
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsValueContainsWithoutLoggingTheSearchedValue() {
        ApplicationConfigurationService service = service();
        var params = new LinkedMultiValueMap<String, String>();
        params.add("valueContains", "internal-value");
        var pageable = PageRequest.of(0, 20);
        when(configurationRepository.findAll(any(Specification.class), eq(pageable)))
            .thenReturn(Page.empty(pageable));

        var result = service.search(params, pageable);

        assertThat(result).isEmpty();
        verify(eventLogger).successAfterCommit(
            eq("CONFIGURATION_PAGE_VIEWED"),
            argThat(data -> !data.containsKey("valueContains") && !data.containsKey("valueFilter"))
        );
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
            lookupService, cacheInvalidator, csvCodec, expressionService, eventLogger
        );
    }
}
