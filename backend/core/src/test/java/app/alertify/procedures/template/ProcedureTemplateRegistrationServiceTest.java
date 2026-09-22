package app.alertify.procedures.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.jpa.repository.ProcedureTemplateDefinitionRepository;
import app.alertify.jpa.repository.ProcedureTemplateOutputDefinitionRepository;
import app.alertify.jpa.repository.ProcedureTemplateParameterDefinitionRepository;
import app.alertify.procedures.model.ProcedureTemplateDefinition;
import app.alertify.procedures.model.ProcedureTemplateOutputDefinition;
import app.alertify.procedures.model.ProcedureTemplateParameterDefinition;
import app.alertify.procedures.templates.CopyFileToNfsProcedureTemplate;
import app.alertify.procedures.templates.ExecutePipeProcedureTemplate;
import app.alertify.procedures.templates.MariaDbBackupProcedureTemplate;
import app.alertify.procedures.templates.OracleDataPumpExportProcedureTemplate;
import app.alertify.procedures.templates.PostgresBackupProcedureTemplate;
import app.alertify.procedures.templates.SqlServerNativeBackupProcedureTemplate;
import app.alertify.procedures.templates.TotpProcedureTemplate;
import app.alertify.procedures.templates.devtools.ConsoleParameterProcedureTemplate;
import app.alertify.procedures.templates.devtools.SimulatedLongRunningProcedureTemplate;
import app.alertify.procedures.templates.devtools.WritableParameterCopyProcedureTemplate;

@ExtendWith(MockitoExtension.class)
class ProcedureTemplateRegistrationServiceTest {
    @Mock private ProcedureTemplateDefinitionRepository templateRepository;
    @Mock private ProcedureTemplateParameterDefinitionRepository parameterRepository;
    @Mock private ProcedureTemplateOutputDefinitionRepository outputRepository;

    @Test
    void registersProcedureTemplatesAndTheirSourceRestrictions() {
        when(templateRepository.findByTemplateKey(anyString())).thenReturn(Optional.empty());
        when(parameterRepository.findAllByTemplate_TemplateKey(anyString())).thenReturn(List.of());
        when(outputRepository.findAllByTemplate_TemplateKeyOrderByOutputOrderAscIdAsc(anyString())).thenReturn(List.of());
        ProcedureTemplateRegistrationService service = new ProcedureTemplateRegistrationService(
                templateRepository, parameterRepository, outputRepository, new DefaultResourceLoader()
        );

        assertThat(service.scanAndRegister()).isEqualTo(10);

        ArgumentCaptor<ProcedureTemplateDefinition> template = ArgumentCaptor.captor();
        verify(templateRepository, org.mockito.Mockito.times(10)).save(template.capture());
        ProcedureTemplateDefinition totp = template.getAllValues().stream()
                .filter(value -> value.getTemplateKey().equals(TotpProcedureTemplate.class.getName()))
                .findFirst().orElseThrow();
        assertThat(totp.isSensitiveResult()).isTrue();
        assertThat(totp.getTags()).singleElement().satisfies(tag ->
                assertThat(tag.nameKey()).isEqualTo("procedures.templateTag.security"));

        assertThat(template.getAllValues()).extracting(ProcedureTemplateDefinition::getTemplateKey)
                .contains(ConsoleParameterProcedureTemplate.class.getName(),
                        WritableParameterCopyProcedureTemplate.class.getName(),
                        SimulatedLongRunningProcedureTemplate.class.getName(),
                        ExecutePipeProcedureTemplate.class.getName(),
                        PostgresBackupProcedureTemplate.class.getName(),
                        MariaDbBackupProcedureTemplate.class.getName(),
                        SqlServerNativeBackupProcedureTemplate.class.getName(),
                        OracleDataPumpExportProcedureTemplate.class.getName(),
                        CopyFileToNfsProcedureTemplate.class.getName());

        ArgumentCaptor<ProcedureTemplateParameterDefinition> parameters = ArgumentCaptor.captor();
        verify(parameterRepository, org.mockito.Mockito.times(46)).save(parameters.capture());
        List<ProcedureTemplateParameterDefinition> totpParameters = parameters.getAllValues().stream()
                .filter(value -> value.getTemplate() == totp).toList();
        assertThat(totpParameters.getFirst().getParameterKey()).isEqualTo("secret");
        assertThat(totpParameters.getFirst().getAllowedSources())
                .containsExactly(AlertParameterSource.SECRET);
        assertThat(totpParameters.get(1).getOptions()).containsExactly("SHA1", "SHA256", "SHA512");

        ProcedureTemplateDefinition postgres = template.getAllValues().stream()
                .filter(value -> value.getTemplateKey().equals(PostgresBackupProcedureTemplate.class.getName()))
                .findFirst().orElseThrow();
        List<ProcedureTemplateParameterDefinition> postgresParameters = parameters.getAllValues().stream()
                .filter(value -> value.getTemplate() == postgres).toList();
        assertThat(postgresParameters).extracting(ProcedureTemplateParameterDefinition::getParameterKey)
                .containsExactly("credentials", "fileName", "gzipCompressionLevel", "excludePrivileges", "discardOwnership");
        assertThat(postgresParameters.get(3).getDefaultValue()).isEqualTo("true");
        assertThat(postgresParameters.get(4).getDefaultValue()).isEqualTo("true");

        ProcedureTemplateDefinition mariaDb = template.getAllValues().stream()
                .filter(value -> value.getTemplateKey().equals(MariaDbBackupProcedureTemplate.class.getName()))
                .findFirst().orElseThrow();
        assertThat(parameters.getAllValues().stream().filter(value -> value.getTemplate() == mariaDb))
                .extracting(ProcedureTemplateParameterDefinition::getParameterKey)
                .containsExactly("credentials", "fileName", "gzipCompressionLevel", "singleTransaction", "includeRoutines");

        ProcedureTemplateDefinition sqlServer = template.getAllValues().stream()
                .filter(value -> value.getTemplateKey().equals(SqlServerNativeBackupProcedureTemplate.class.getName()))
                .findFirst().orElseThrow();
        assertThat(parameters.getAllValues().stream().filter(value -> value.getTemplate() == sqlServer))
                .extracting(ProcedureTemplateParameterDefinition::getParameterKey)
                .containsExactly("credentials", "fileName", "serverBackupDirectory", "stripes", "zipCompressionLevel",
                        "compression", "checksum", "copyOnly", "deleteFromServer");

        ProcedureTemplateDefinition oracle = template.getAllValues().stream()
                .filter(value -> value.getTemplateKey().equals(OracleDataPumpExportProcedureTemplate.class.getName()))
                .findFirst().orElseThrow();
        assertThat(parameters.getAllValues().stream().filter(value -> value.getTemplate() == oracle))
                .extracting(ProcedureTemplateParameterDefinition::getParameterKey)
                .containsExactly("credentials", "fileName", "directoryName", "exportMode", "schemas",
                        "dataPumpCompression", "zipCompressionLevel", "deleteFromServer");

        ArgumentCaptor<ProcedureTemplateOutputDefinition> outputs = ArgumentCaptor.captor();
        verify(outputRepository, org.mockito.Mockito.times(4)).save(outputs.capture());
        assertThat(outputs.getAllValues()).extracting(ProcedureTemplateOutputDefinition::getTemplate)
                .containsExactlyInAnyOrder(postgres, mariaDb, sqlServer, oracle);
        assertThat(outputs.getAllValues()).extracting(ProcedureTemplateOutputDefinition::getOutputKey)
                .containsOnly("backup");
    }
}
