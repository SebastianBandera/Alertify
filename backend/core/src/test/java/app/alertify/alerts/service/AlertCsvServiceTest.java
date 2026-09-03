package app.alertify.alerts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.alerts.api.AlertCreateRequest;
import app.alertify.alerts.api.AlertImportResult;
import app.alertify.alerts.api.AlertUpdateRequest;
import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertParameterValue;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.alerts.model.AlertTemplateParameterDefinition;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.api.error.InvalidAlertImportException;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.entity.Tag;
import app.alertify.jpa.entity.TagScope;
import app.alertify.jpa.repository.AlertParameterValueRepository;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.worker.contract.WorkerCapability;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.StringNode;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertCsvServiceTest {

    private static final String TEMPLATE_KEY = "app.alertify.alerts.templates.HttpsCertificateExpiryAlertTemplate";
    private static final String HEADER =
            "name,description,templateKey,cronExpression,enabled,allowConcurrentExecutions,parameters,tags";

    @Mock private AlertRepository alertRepository;
    @Mock private AlertParameterValueRepository parameterValueRepository;
    @Mock private AlertTemplateDefinitionRepository templateRepository;
    @Mock private ApplicationConfigurationRepository configurationRepository;
    @Mock private ApplicationSecretRepository secretRepository;
    @Mock private TagRepository tagRepository;
    @Mock private AlertManagementService alertManagementService;

    private final AlertTemplateDefinition template = template(TEMPLATE_KEY, 7L);

    @Test
    void createsAlertsThatDoNotExistYet() {
        stubCatalog();
        when(alertRepository.findAll()).thenReturn(List.of());

        AlertImportResult result = service().importCsv(
                file("nueva,Chequeo diario," + TEMPLATE_KEY + ",0 0 8 * * *,true,false,"
                        + "\"[{\"\"key\"\":\"\"endpoint\"\",\"\"source\"\":\"\"TEXT\"\",\"\"value\"\":\"\"https://ejemplo.com.uy\"\"}]\",[]")
        );

        assertThat(result).isEqualTo(new AlertImportResult(1, 1, 0, 0, 0));
        ArgumentCaptor<AlertCreateRequest> captor = ArgumentCaptor.forClass(AlertCreateRequest.class);
        verify(alertManagementService).create(captor.capture());
        AlertCreateRequest request = captor.getValue();
        assertThat(request.templateId()).isEqualTo(7L);
        assertThat(request.name()).isEqualTo("nueva");
        assertThat(request.description()).isEqualTo("Chequeo diario");
        assertThat(request.cronExpression()).isEqualTo("0 0 8 * * *");
        assertThat(request.enabled()).isTrue();
        assertThat(request.parameters()).singleElement().satisfies(parameter -> {
            assertThat(parameter.parameterKey()).isEqualTo("endpoint");
            assertThat(parameter.source()).isEqualTo(AlertParameterSource.TEXT);
            assertThat(parameter.textValue()).isEqualTo("https://ejemplo.com.uy");
        });
        verify(alertManagementService, never()).update(anyLong(), any());
    }

    @Test
    void updatesExistingAlertWhenTheRowDiffers() {
        stubCatalog();
        Alert existing = alert("nueva", null, "0 0 8 * * *", true, false);
        when(alertRepository.findAll()).thenReturn(List.of(existing));
        when(parameterValueRepository.findAllByAlertIdOrdered(1L)).thenReturn(List.of());

        AlertImportResult result = service().importCsv(
                file("nueva,," + TEMPLATE_KEY + ",0 0 9 * * *,true,false,[],[]")
        );

        assertThat(result).isEqualTo(new AlertImportResult(1, 0, 1, 0, 0));
        ArgumentCaptor<AlertUpdateRequest> captor = ArgumentCaptor.forClass(AlertUpdateRequest.class);
        verify(alertManagementService).update(eq(1L), captor.capture());
        assertThat(captor.getValue().cronExpression()).isEqualTo("0 0 9 * * *");
        assertThat(captor.getValue().version()).isZero();
    }

    @Test
    void reportsUnchangedAndSkipsWritesWhenTheRowMatchesTheStoredAlert() {
        stubCatalog();
        Alert existing = alert("nueva", "Chequeo", "0 0 8 * * *", true, false);
        ApplicationConfiguration configuration = configuration("DIAS_AVISO", 4L);
        AlertParameterValue value = AlertParameterValue.configuration(
                existing, parameter("warningDays", 1), configuration
        );
        when(alertRepository.findAll()).thenReturn(List.of(existing));
        when(parameterValueRepository.findAllByAlertIdOrdered(1L)).thenReturn(List.of(value));

        AlertImportResult result = service().importCsv(
                file("nueva,Chequeo," + TEMPLATE_KEY + ",0 0 8 * * *,true,false,"
                        + "\"[{\"\"key\"\":\"\"warningDays\"\",\"\"source\"\":\"\"CONFIGURATION\"\",\"\"value\"\":\"\"DIAS_AVISO\"\"}]\",[]")
        );

        assertThat(result).isEqualTo(new AlertImportResult(1, 0, 0, 1, 0));
        verify(alertManagementService, never()).create(any());
        verify(alertManagementService, never()).update(anyLong(), any());
    }

    @Test
    void createsMissingTagsWithAlertScope() {
        stubCatalog();
        when(alertRepository.findAll()).thenReturn(List.of());
        when(tagRepository.findAllByScope(TagScope.ALERT)).thenReturn(List.of());
        when(tagRepository.save(any())).thenAnswer(invocation -> {
            Tag tag = invocation.getArgument(0);
            ReflectionTestUtils.setField(tag, "id", 55L);
            return tag;
        });

        AlertImportResult result = service().importCsv(
                file("nueva,," + TEMPLATE_KEY + ",0 0 8 * * *,true,false,[],"
                        + "\"[{\"\"name\"\":\"\"prod\"\",\"\"color\"\":\"\"#FF0000\"\"}]\"")
        );

        assertThat(result.tagsCreated()).isEqualTo(1);
        ArgumentCaptor<Tag> captor = ArgumentCaptor.forClass(Tag.class);
        verify(tagRepository).save(captor.capture());
        assertThat(captor.getValue().getScope()).isEqualTo(TagScope.ALERT);
        assertThat(captor.getValue().getName()).isEqualTo("prod");
    }

    @Test
    void rejectsUnknownTemplateKey() {
        stubCatalog();
        when(alertRepository.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service().importCsv(
                file("nueva,,app.alertify.alerts.templates.NoExiste,0 0 8 * * *,true,false,[],[]")
        ))
                .isInstanceOf(InvalidAlertImportException.class)
                .hasMessage("CSV row 2: template 'app.alertify.alerts.templates.NoExiste' was not found");
    }

    @Test
    void rejectsTemplateChangeOnAnExistingAlert() {
        AlertTemplateDefinition other = template("app.alertify.alerts.templates.InternetConnectionAlertTemplate", 9L);
        stubCatalog();
        when(templateRepository.findAll()).thenReturn(List.of(template, other));
        when(alertRepository.findAll()).thenReturn(List.of(alert("nueva", null, "0 0 8 * * *", true, false)));

        assertThatThrownBy(() -> service().importCsv(
                file("nueva,,app.alertify.alerts.templates.InternetConnectionAlertTemplate,0 0 8 * * *,true,false,[],[]")
        ))
                .isInstanceOf(InvalidAlertImportException.class)
                .hasMessageContaining("CSV row 2: alert 'nueva' already uses template '" + TEMPLATE_KEY + "'")
                .hasMessageContaining("cannot be changed");
    }

    @Test
    void rejectsUnknownConfigurationAndSecretNames() {
        stubCatalog();
        when(alertRepository.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service().importCsv(
                file("nueva,," + TEMPLATE_KEY + ",0 0 8 * * *,true,false,"
                        + "\"[{\"\"key\"\":\"\"warningDays\"\",\"\"source\"\":\"\"CONFIGURATION\"\",\"\"value\"\":\"\"NO_EXISTE\"\"}]\",[]")
        ))
                .isInstanceOf(InvalidAlertImportException.class)
                .hasMessage("CSV row 2: configuration 'NO_EXISTE' was not found");

        assertThatThrownBy(() -> service().importCsv(
                file("nueva,," + TEMPLATE_KEY + ",0 0 8 * * *,true,false,"
                        + "\"[{\"\"key\"\":\"\"apiToken\"\",\"\"source\"\":\"\"SECRET\"\",\"\"value\"\":\"\"NO_EXISTE\"\"}]\",[]")
        ))
                .isInstanceOf(InvalidAlertImportException.class)
                .hasMessage("CSV row 2: secret 'NO_EXISTE' was not found");
    }

    @Test
    void rejectsEmptyUpload() {
        assertThatThrownBy(() -> service().importCsv(
                new MockMultipartFile("file", "alertify-alerts.csv", "text/csv", new byte[0])
        ))
                .isInstanceOf(InvalidAlertImportException.class)
                .hasMessage("A non-empty CSV file is required");
    }

    @Test
    void exportNeverIncludesSecretValues() {
        Alert alert = alert("con-secreto", null, "0 0 8 * * *", true, false);
        ApplicationSecret secret = new ApplicationSecret(
                "TOKEN_API", null, "cipher".getBytes(StandardCharsets.UTF_8), new byte[12],
                new byte[32], new byte[16], (short) 1, Set.of()
        );
        when(alertRepository.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(alert));
        when(parameterValueRepository.findAllByAlertIdOrdered(1L)).thenReturn(
                List.of(AlertParameterValue.secret(alert, parameter("apiToken", 1), secret))
        );

        String csv = new String(service().exportCsv(), StandardCharsets.UTF_8);

        assertThat(csv).contains("TOKEN_API");
        assertThat(csv).doesNotContain("cipher");
    }

    private AlertCsvService service() {
        return new AlertCsvService(
                alertRepository, parameterValueRepository, templateRepository, configurationRepository,
                secretRepository, tagRepository, alertManagementService,
                new AlertCsvCodec(JsonMapper.builder().build())
        );
    }

    private void stubCatalog() {
        when(templateRepository.findAll()).thenReturn(List.of(template));
        when(tagRepository.findAllByScope(TagScope.ALERT)).thenReturn(List.of());
        when(configurationRepository.findAll()).thenReturn(List.of(configuration("DIAS_AVISO", 4L)));
        when(secretRepository.findAll()).thenReturn(List.of());
    }

    private static MockMultipartFile file(String... rows) {
        StringBuilder csv = new StringBuilder("\uFEFF").append(HEADER).append("\r\n");
        for (String row : rows)
            csv.append(row).append("\r\n");

        return new MockMultipartFile(
                "file", "alertify-alerts.csv", "text/csv", csv.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private static AlertTemplateDefinition template(String templateKey, long id) {
        AlertTemplateDefinition definition = new AlertTemplateDefinition(
                templateKey, "name.key", "description.key", "source/path.java", WorkerCapability.STANDARD
        );
        ReflectionTestUtils.setField(definition, "id", id);
        return definition;
    }

    private static ApplicationConfiguration configuration(String name, long id) {
        ApplicationConfiguration configuration = new ApplicationConfiguration(
                name, null, ConfigurationValueType.STRING, StringNode.valueOf("30"), Set.of()
        );
        ReflectionTestUtils.setField(configuration, "id", id);
        return configuration;
    }

    private AlertTemplateParameterDefinition parameter(String key, int order) {
        AlertTemplateParameterDefinition definition = new AlertTemplateParameterDefinition(
                template, key, key + ".label", key + ".description", "java.lang.String",
                List.of(), true, null, order, true
        );
        ReflectionTestUtils.setField(definition, "id", (long) order);
        return definition;
    }

    private Alert alert(String name, String description, String cron, boolean enabled, boolean allowConcurrent) {
        Alert alert = new Alert(template, name, description, cron, enabled, allowConcurrent, Set.of());
        ReflectionTestUtils.setField(alert, "id", 1L);
        return alert;
    }
}
