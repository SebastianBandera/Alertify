package app.alertify.alerts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertParameterValue;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.alerts.model.AlertTemplateParameterDefinition;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.api.error.InvalidAlertImportException;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.worker.contract.WorkerCapability;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.StringNode;

class AlertCsvCodecTest {

    private static final String TEMPLATE_KEY = "app.alertify.alerts.templates.HttpsCertificateExpiryAlertTemplate";
    private static final String HEADER =
            "name,description,templateKey,cronExpression,enabled,allowConcurrentExecutions,parameters,tags";

    private final AlertCsvCodec codec = new AlertCsvCodec(JsonMapper.builder().build());

    @Test
    void writesBomHeaderAndOneRowPerAlert() {
        AlertTemplateDefinition template = template();
        Alert alert = alert(template, "cert-check", "Vencimiento", "0 0 8 * * *", true, false);

        String csv = new String(codec.write(List.of(alert), Map.of()), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("\uFEFF" + HEADER + "\r\n");
        assertThat(csv).contains("cert-check,Vencimiento," + TEMPLATE_KEY + ",0 0 8 * * *,true,false,[],[]");
        assertThat(csv).endsWith("\r\n");
    }

    @Test
    void exportsSecretNameButNeverItsValue() {
        AlertTemplateDefinition template = template();
        AlertTemplateParameterDefinition definition = parameter(template, "apiToken", 1);
        Alert alert = alert(template, "with-secret", null, "0 0 * * * *", true, false);
        ApplicationSecret secret = new ApplicationSecret(
                "TOKEN_API", null, "cipher".getBytes(StandardCharsets.UTF_8), new byte[12],
                new byte[32], new byte[16], (short) 1, Set.of()
        );
        AlertParameterValue value = AlertParameterValue.secret(alert, definition, secret);

        String csv = new String(
                codec.write(List.of(alert), values(alert, List.of(value))), StandardCharsets.UTF_8
        );

        assertThat(csv).contains("\"\"key\"\":\"\"apiToken\"\",\"\"source\"\":\"\"SECRET\"\",\"\"value\"\":\"\"TOKEN_API\"\"");
        assertThat(csv).doesNotContain("cipher");
    }

    @Test
    void roundTripsAllThreeParameterSources() {
        AlertTemplateDefinition template = template();
        Alert alert = alert(template, "mixto", "Descripción, con coma", "0 */5 * * * *", false, true);
        ApplicationConfiguration configuration = new ApplicationConfiguration(
                "DIAS_AVISO", null, ConfigurationValueType.STRING, StringNode.valueOf("30"), Set.of()
        );
        ApplicationSecret secret = new ApplicationSecret(
                "TOKEN_API", null, new byte[8], new byte[12], new byte[32], new byte[16], (short) 1, Set.of()
        );
        List<AlertParameterValue> parameterValues = List.of(
                AlertParameterValue.text(alert, parameter(template, "endpoint", 1), "https://ejemplo.com.uy"),
                AlertParameterValue.configuration(alert, parameter(template, "warningDays", 2), configuration),
                AlertParameterValue.secret(alert, parameter(template, "apiToken", 3), secret)
        );

        byte[] csv = codec.write(List.of(alert), values(alert, parameterValues));
        List<AlertCsvCodec.ImportRow> rows = codec.read(csv);

        assertThat(rows).hasSize(1);
        AlertCsvCodec.ImportRow row = rows.getFirst();
        assertThat(row.rowNumber()).isEqualTo(2);
        assertThat(row.name()).isEqualTo("mixto");
        assertThat(row.description()).isEqualTo("Descripción, con coma");
        assertThat(row.templateKey()).isEqualTo(TEMPLATE_KEY);
        assertThat(row.cronExpression()).isEqualTo("0 */5 * * * *");
        assertThat(row.enabled()).isFalse();
        assertThat(row.allowConcurrentExecutions()).isTrue();
        assertThat(row.parameters()).containsExactly(
                new AlertCsvCodec.ImportParameter("endpoint", AlertParameterSource.TEXT, "https://ejemplo.com.uy"),
                new AlertCsvCodec.ImportParameter("warningDays", AlertParameterSource.CONFIGURATION, "DIAS_AVISO"),
                new AlertCsvCodec.ImportParameter("apiToken", AlertParameterSource.SECRET, "TOKEN_API")
        );
    }

    @Test
    void readsTagsAndBlankDescriptionAsNull() {
        List<AlertCsvCodec.ImportRow> rows = codec.read(
                csv("alpha,," + TEMPLATE_KEY + ",0 0 * * * *,true,false,[],\"[{\"\"name\"\":\"\"prod\"\",\"\"color\"\":\"\"#ff0000\"\"}]\"")
        );

        assertThat(rows.getFirst().description()).isNull();
        assertThat(rows.getFirst().tags()).containsExactly(new AlertCsvCodec.ImportTag("prod", "#FF0000"));
    }

    @Test
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> codec.read(new byte[0]))
                .isInstanceOf(InvalidAlertImportException.class)
                .hasMessage("The CSV file is empty");
    }

    @Test
    void rejectsUnexpectedHeader() {
        assertThatThrownBy(() -> codec.read("name,description\r\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidAlertImportException.class)
                .hasMessageStartingWith("CSV header must be exactly: " + HEADER);
    }

    @Test
    void rejectsRowWithWrongColumnCount() {
        assertThatThrownBy(() -> codec.read(csv("alpha,,x,0 0 * * * *,true,false,[]")))
                .isInstanceOf(InvalidAlertImportException.class)
                .hasMessage("CSV row 2: expected 8 columns");
    }

    @Test
    void rejectsDuplicateNameIgnoringCase() {
        assertThatThrownBy(() -> codec.read(
                csv(
                        "alpha,," + TEMPLATE_KEY + ",0 0 * * * *,true,false,[],[]",
                        "ALPHA,," + TEMPLATE_KEY + ",0 0 * * * *,true,false,[],[]"
                )
        ))
                .isInstanceOf(InvalidAlertImportException.class)
                .hasMessage("CSV row 3: duplicate alert name also present on row 2");
    }

    @Test
    void rejectsMissingTemplateKeyAndCron() {
        assertThatThrownBy(() -> codec.read(csv("alpha,,,0 0 * * * *,true,false,[],[]")))
                .isInstanceOf(InvalidAlertImportException.class)
                .hasMessage("CSV row 2: templateKey is required");

        assertThatThrownBy(() -> codec.read(csv("alpha,," + TEMPLATE_KEY + ",,true,false,[],[]")))
                .isInstanceOf(InvalidAlertImportException.class)
                .hasMessage("CSV row 2: cronExpression is required");
    }

    @Test
    void rejectsNonBooleanFlags() {
        assertThatThrownBy(() -> codec.read(csv("alpha,," + TEMPLATE_KEY + ",0 0 * * * *,yes,false,[],[]")))
                .isInstanceOf(InvalidAlertImportException.class)
                .hasMessage("CSV row 2: enabled must be true or false");
    }

    @Test
    void rejectsInvalidParameterSource() {
        assertThatThrownBy(() -> codec.read(
                csv(
                        "alpha,," + TEMPLATE_KEY + ",0 0 * * * *,true,false,"
                                + "\"[{\"\"key\"\":\"\"endpoint\"\",\"\"source\"\":\"\"ENV\"\",\"\"value\"\":\"\"x\"\"}]\",[]"
                )
        ))
                .isInstanceOf(InvalidAlertImportException.class)
                .hasMessage("CSV row 2: parameter 'endpoint' has an invalid source");
    }

    @Test
    void rejectsDuplicateParameterKey() {
        assertThatThrownBy(() -> codec.read(
                csv(
                        "alpha,," + TEMPLATE_KEY + ",0 0 * * * *,true,false,"
                                + "\"[{\"\"key\"\":\"\"endpoint\"\",\"\"source\"\":\"\"TEXT\"\",\"\"value\"\":\"\"a\"\"},"
                                + "{\"\"key\"\":\"\"endpoint\"\",\"\"source\"\":\"\"TEXT\"\",\"\"value\"\":\"\"b\"\"}]\",[]"
                )
        ))
                .isInstanceOf(InvalidAlertImportException.class)
                .hasMessage("CSV row 2: parameter 'endpoint' is listed more than once");
    }

    @Test
    void rejectsBoundParameterWithoutReferencedName() {
        assertThatThrownBy(() -> codec.read(
                csv(
                        "alpha,," + TEMPLATE_KEY + ",0 0 * * * *,true,false,"
                                + "\"[{\"\"key\"\":\"\"endpoint\"\",\"\"source\"\":\"\"CONFIGURATION\"\",\"\"value\"\":\"\" \"\"}]\",[]"
                )
        ))
                .isInstanceOf(InvalidAlertImportException.class)
                .hasMessage("CSV row 2: parameter 'endpoint' requires the referenced name");
    }

    @Test
    void rejectsParametersThatAreNotAJsonArray() {
        assertThatThrownBy(() -> codec.read(csv("alpha,," + TEMPLATE_KEY + ",0 0 * * * *,true,false,{},[]")))
                .isInstanceOf(InvalidAlertImportException.class)
                .hasMessage("CSV row 2: parameters must be a JSON array");

        assertThatThrownBy(() -> codec.read(csv("alpha,," + TEMPLATE_KEY + ",0 0 * * * *,true,false,nope,[]")))
                .isInstanceOf(InvalidAlertImportException.class)
                .hasMessage("CSV row 2: parameters is not valid JSON");
    }

    @Test
    void rejectsInvalidTagColor() {
        assertThatThrownBy(() -> codec.read(
                csv(
                        "alpha,," + TEMPLATE_KEY + ",0 0 * * * *,true,false,[],"
                                + "\"[{\"\"name\"\":\"\"prod\"\",\"\"color\"\":\"\"red\"\"}]\""
                )
        ))
                .isInstanceOf(InvalidAlertImportException.class)
                .hasMessage("CSV row 2: tag color must use #RRGGBB format");
    }

    @Test
    void rejectsMoreRowsThanTheLimit() {
        StringBuilder csv = new StringBuilder(HEADER).append("\r\n");
        for (int index = 0; index < 10_001; index++)
            csv.append("alert-").append(index).append(",,").append(TEMPLATE_KEY).append(",0 0 * * * *,true,false,[],[]\r\n");

        byte[] content = csv.toString().getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> codec.read(content))
                .isInstanceOf(InvalidAlertImportException.class)
                .hasMessage("CSV contains more than 10000 alerts");
    }

    private static byte[] csv(String... rows) {
        StringBuilder csv = new StringBuilder("\uFEFF").append(HEADER).append("\r\n");
        for (String row : rows)
            csv.append(row).append("\r\n");

        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Map<Long, List<AlertParameterValue>> values(Alert alert, List<AlertParameterValue> values) {
        Map<Long, List<AlertParameterValue>> byAlertId = new HashMap<>();
        byAlertId.put(alert.getId(), values);
        return byAlertId;
    }

    private static AlertTemplateDefinition template() {
        return new AlertTemplateDefinition(
                TEMPLATE_KEY, "alerts.template.httpsCertificate.name",
                "alerts.template.httpsCertificate.description",
                "app/alertify/alerts/templates/HttpsCertificateExpiryAlertTemplate.java",
                WorkerCapability.STANDARD
        );
    }

    private static AlertTemplateParameterDefinition parameter(AlertTemplateDefinition template, String key, int order) {
        AlertTemplateParameterDefinition definition = new AlertTemplateParameterDefinition(
                template, key, key + ".label", key + ".description", "java.lang.String",
                List.of(), true, null, false, order, true
        );
        ReflectionTestUtils.setField(definition, "id", (long) order);
        return definition;
    }

    private static Alert alert(AlertTemplateDefinition template, String name, String description, String cron, boolean enabled, boolean allowConcurrent) {
        Alert alert = new Alert(template, name, description, cron, enabled, allowConcurrent, Set.of());
        ReflectionTestUtils.setField(alert, "id", 1L);
        return alert;
    }
}
