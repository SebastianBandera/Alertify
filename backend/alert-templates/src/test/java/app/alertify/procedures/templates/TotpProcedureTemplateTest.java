package app.alertify.procedures.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import app.alertify.procedures.ProcedureExecutionContext;
import app.alertify.procedures.template.annotation.ProcedureTemplate;
import tools.jackson.databind.JsonNode;

class TotpProcedureTemplateTest {

    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void generatesTheRfc6238Sha1VectorAndItsValidityWindow() throws Exception {
        TotpProcedureTemplate procedure = new TotpProcedureTemplate(RFC_SECRET, "SHA1", 8, 30);

        JsonNode result = procedure.execute(new ProcedureExecutionContext(
                Instant.ofEpochSecond(59), Map.of()
        ));

        assertThat(result.path("code").stringValue()).isEqualTo("94287082");
        assertThat(result.path("validFrom").stringValue()).isEqualTo("1970-01-01T00:00:30Z");
        assertThat(result.path("validUntil").stringValue()).isEqualTo("1970-01-01T00:01:00Z");
    }

    @Test
    void marksTheTemplateResultAsSensitive() {
        ProcedureTemplate metadata = TotpProcedureTemplate.class.getAnnotation(ProcedureTemplate.class);

        assertThat(metadata.sensitiveResult()).isTrue();
    }

    @Test
    void rejectsAnInvalidBase32Secret() {
        TotpProcedureTemplate procedure = new TotpProcedureTemplate("NOT_A_BASE32_SECRET!", "SHA1", 6, 30);

        assertThatThrownBy(() -> procedure.execute(new ProcedureExecutionContext(Instant.EPOCH, Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Base32");
    }
}
