package app.alertify.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.LinkedMultiValueMap;

import app.alertify.configuration.api.BinaryConfigurationCreateRequest;
import app.alertify.configuration.api.ConfigurationUpdateRequest;
import app.alertify.jpa.entity.ConfigurationBinaryValue;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.jpa.specification.InvalidFilterException;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.jpa.repository.ConfigurationBinaryValueRepository;
import app.alertify.binary.BinaryPayloadService;
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
    @Mock private ConfigurationBinaryValueRepository binaryRepository;
    @Mock private BinaryPayloadService binaryPayloadService;

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

    @Test
    void createsEmptyBinaryWhenNoFileIsUploaded() {
        byte[] zip = new byte[] { 80, 75, 5, 6 };
        when(binaryPayloadService.prepare(eq(new byte[0]), isNull(), isNull()))
            .thenReturn(new BinaryPayloadService.PreparedBinary("binary.bin", "application/octet-stream", 0, zip.length, new byte[32], zip));
        when(configurationRepository.saveAndFlush(any(ApplicationConfiguration.class))).thenAnswer(invocation -> {
            ApplicationConfiguration saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 7L);
            return saved;
        });
        ApplicationConfigurationService service = service();

        var response = service.createBinary(new BinaryConfigurationCreateRequest("blob", null, Set.of(), false), null);

        assertThat(response.valueType()).isEqualTo(ConfigurationValueType.BINARY);
        assertThat(response.binaryFileName()).isEqualTo("binary.bin");
        assertThat(response.binarySize()).isZero();
        verify(binaryRepository).saveAndFlush(argThat((ConfigurationBinaryValue value) -> value.getConfigurationId() == 7L && java.util.Arrays.equals(value.getZipValue(), zip)));
    }

    @Test
    void keepsTheNameAndTypeOfAnEmptyUploadedFile() {
        byte[] zip = new byte[] { 80, 75, 5, 6 };
        when(binaryPayloadService.prepare(eq(new byte[0]), eq("empty.dat"), eq("text/plain")))
            .thenReturn(new BinaryPayloadService.PreparedBinary("empty.dat", "text/plain", 0, zip.length, new byte[32], zip));
        when(configurationRepository.saveAndFlush(any(ApplicationConfiguration.class))).thenAnswer(invocation -> {
            ApplicationConfiguration saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 8L);
            return saved;
        });
        ApplicationConfigurationService service = service();

        var response = service.createBinary(
            new BinaryConfigurationCreateRequest("blob", null, Set.of(), false),
            new MockMultipartFile("file", "empty.dat", "text/plain", new byte[0])
        );

        assertThat(response.binaryFileName()).isEqualTo("empty.dat");
        assertThat(response.binaryContentType()).isEqualTo("text/plain");
        assertThat(response.binarySize()).isZero();
    }

    private ApplicationConfigurationService service() {
        return new ApplicationConfigurationService(
            configurationRepository, tagRepository, new ConfigurationValueValidator(),
            lookupService, cacheInvalidator, csvCodec, expressionService, eventLogger, binaryRepository, binaryPayloadService
        );
    }
}
