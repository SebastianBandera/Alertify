package app.alertify.procedures.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.api.error.InvalidProcedureImportException;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.procedures.model.Procedure;
import app.alertify.procedures.model.ProcedureParameterValue;
import app.alertify.procedures.model.ProcedureTemplateDefinition;
import app.alertify.procedures.model.ProcedureTemplateParameterDefinition;
import app.alertify.worker.contract.WorkerCapability;
import tools.jackson.databind.json.JsonMapper;

class ProcedureCsvCodecTest {
    private final ProcedureCsvCodec codec = new ProcedureCsvCodec(JsonMapper.builder().build());

    @Test
    void exportsOnlyASecretReferenceName() {
        ProcedureTemplateDefinition template = template();
        Procedure procedure = new Procedure(template, "totp", null, true, Set.of());
        ReflectionTestUtils.setField(procedure, "id", 7L);
        ProcedureTemplateParameterDefinition parameter = new ProcedureTemplateParameterDefinition(
                template, "secret", "label", "description", String.class.getName(), List.of(),
                true, null, false, 1, true, List.of(AlertParameterSource.SECRET)
        );
        ApplicationSecret secret = new ApplicationSecret("TOTP_SECRET", null,
                "cipher-text".getBytes(StandardCharsets.UTF_8), new byte[12], new byte[32],
                new byte[16], (short) 1, Set.of());

        String csv = new String(codec.write(List.of(procedure), Map.of(7L,
                List.of(ProcedureParameterValue.secret(procedure, parameter, secret)))), StandardCharsets.UTF_8);

        assertThat(csv).contains("TOTP_SECRET").doesNotContain("cipher-text");
    }

    @Test
    void readsProcedureReferencesForRecursiveImports() {
        String csv = "\uFEFFname,description,templateKey,enabled,parameters,tags\r\n"
                + "A,,template.A,true,\"[{\"\"key\"\":\"\"next\"\",\"\"source\"\":\"\"PROCEDURE\"\",\"\"value\"\":\"\"B\"\"}]\",[]\r\n";

        ProcedureCsvCodec.ImportRow row = codec.read(csv.getBytes(StandardCharsets.UTF_8)).getFirst();

        assertThat(row.parameters()).singleElement().satisfies(parameter -> {
            assertThat(parameter.source()).isEqualTo(AlertParameterSource.PROCEDURE);
            assertThat(parameter.value()).isEqualTo("B");
        });
    }

    @Test
    void rejectsAnUnexpectedHeader() {
        assertThatThrownBy(() -> codec.read("name\r\nvalue\r\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidProcedureImportException.class)
                .hasMessageContaining("header");
    }

    private static ProcedureTemplateDefinition template() {
        return new ProcedureTemplateDefinition("template.A", "name", "description", "source.java",
                WorkerCapability.STANDARD, true, List.of());
    }
}
