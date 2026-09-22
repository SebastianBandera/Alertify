package app.alertify.procedures.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import app.alertify.procedures.ProcedureExecutionContext;

class OracleDataPumpExportProcedureTemplateTest {

    @Test
    void buildsSynchronousDataPumpBlockWithOptionalSchemaFilter() {
        var schemaBlock = OracleDataPumpExportProcedureTemplate.exportBlock(true);
        var fullBlock = OracleDataPumpExportProcedureTemplate.exportBlock(false);

        assertThat(schemaBlock).contains("DBMS_DATAPUMP.OPEN(operation => 'EXPORT', job_mode => ?, job_name => ?",
                "KU$_FILE_TYPE_DUMP_FILE, reusefile => 1", "KU$_FILE_TYPE_LOG_FILE, reusefile => 1",
                "METADATA_FILTER(handle => h, name => 'SCHEMA_EXPR', value => ?)",
                "SET_PARAMETER(handle => h, name => 'COMPRESSION', value => ?)",
                "DBMS_DATAPUMP.WAIT_FOR_JOB(h, s)", "? := s;");
        assertThat(schemaBlock.chars().filter(character -> character == '?').count()).isEqualTo(9);
        assertThat(fullBlock).doesNotContain("METADATA_FILTER");
        assertThat(fullBlock.chars().filter(character -> character == '?').count()).isEqualTo(8);
    }

    @Test
    void quotesSchemaListForTheMetadataFilter() {
        assertThat(OracleDataPumpExportProcedureTemplate.schemaExpression(List.of("HR"))).isEqualTo("IN ('HR')");
        assertThat(OracleDataPumpExportProcedureTemplate.schemaExpression(List.of("HR", "SALES"))).isEqualTo("IN ('HR', 'SALES')");
    }

    @Test
    void validatesIdentifiersAndLogicalFileNames() {
        assertThat(OracleDataPumpExportProcedureTemplate.validateIdentifier(" data_pump_dir ", "directoryName")).isEqualTo("DATA_PUMP_DIR");
        assertThatThrownBy(() -> OracleDataPumpExportProcedureTemplate.validateIdentifier("HR'; DROP", "schemas"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schemas");
        assertThatThrownBy(() -> OracleDataPumpExportProcedureTemplate.validateIdentifier("1ABC", "directoryName"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(OracleDataPumpExportProcedureTemplate.baseName("nightly.dmp")).isEqualTo("nightly");
        assertThat(OracleDataPumpExportProcedureTemplate.baseName("nightly.zip")).isEqualTo("nightly");
        assertThatThrownBy(() -> OracleDataPumpExportProcedureTemplate.baseName("../nightly"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void derivesJobNameFromExecutionTimeAndCountsLogErrors() {
        var context = new ProcedureExecutionContext(Instant.ofEpochMilli(0x1234ABCDL), Map.of());

        assertThat(OracleDataPumpExportProcedureTemplate.jobName(context)).isEqualTo("ALERTIFY_1234ABCD");
        assertThat(OracleDataPumpExportProcedureTemplate.countErrors("Starting job\nORA-39001: invalid argument\nORA-31600: bad\nDone")).isEqualTo(2);
    }
}
