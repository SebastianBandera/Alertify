package app.alertify.ai.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import app.alertify.ai.api.AiProcedureParameterSource;
import app.alertify.ai.api.AiProcedureParameterValue;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.alerts.model.AlertTemplateParameterDefinition;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.PipeRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.jpa.repository.ProcedureTemplateDefinitionRepository;
import app.alertify.jpa.repository.ProcedureTemplateParameterDefinitionRepository;
import app.alertify.worker.contract.KubeconfigCredentials;
import app.alertify.worker.contract.WorkerCapability;

/** Requires an empty disposable database; never points at the application's database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "ALERTIFY_TEMPLATE_TEST_DATABASE_URL", matches = "jdbc:postgresql:.+")
class AiTemplateBindingJpaIntegrationTest {
    private final List<String> statements = new ArrayList<>();
    private LocalContainerEntityManagerFactoryBean factory;
    private TransactionTemplate transactions;
    private JpaRepositoryFactory repositories;
    private AiTemplateParametersService service;
    private long secretId;

    @BeforeAll
    void initializeAnEmptyDisposableDatabase() throws Exception {
        var dataSource = new DriverManagerDataSource(System.getenv("ALERTIFY_TEMPLATE_TEST_DATABASE_URL"), "postgres", "");
        var jdbc = new JdbcTemplate(dataSource);
        for (String schema : List.of("core", "audit", "secrets"))
            assertNull(jdbc.queryForObject("SELECT to_regnamespace(?)::text", String.class, schema));

        try (var files = Files.list(Path.of(System.getenv("ALERTIFY_TEMPLATE_TEST_MIGRATIONS")))) {
            for (var migration : files.filter(path -> path.getFileName().toString().matches("[0-9]+\\..*\\.sql"))
                    .sorted(Comparator.comparingInt(path -> Integer.parseInt(path.getFileName().toString().split("\\.")[0]))).toList())
                jdbc.execute(Files.readString(migration));

        }
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setPackagesToScan("app.alertify");
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.jdbc.time_zone", "UTC",
                "hibernate.session_factory.statement_inspector", (StatementInspector) sql -> { statements.add(sql.toLowerCase(Locale.ROOT)); return sql; }));
        factory.afterPropertiesSet();
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        transactions = new TransactionTemplate(new JpaTransactionManager(factory.getObject()));
        repositories = new JpaRepositoryFactory(entityManager);
        var alerts = repositories.getRepository(AlertTemplateDefinitionRepository.class);
        var parameters = repositories.getRepository(AlertTemplateParameterDefinitionRepository.class);
        var procedures = repositories.getRepository(ProcedureTemplateDefinitionRepository.class);
        var procedureParameters = repositories.getRepository(ProcedureTemplateParameterDefinitionRepository.class);
        service = new AiTemplateParametersService(alerts, procedures, parameters, procedureParameters,
                repositories.getRepository(ApplicationConfigurationRepository.class), repositories.getRepository(ApplicationSecretRepository.class),
                repositories.getRepository(ProcedureRepository.class), repositories.getRepository(PipeRepository.class));
        transactions.executeWithoutResult(status -> {
            var template = alerts.saveAndFlush(new AlertTemplateDefinition("test.MetadataOnly", "test.name", "test.description", "test.java", WorkerCapability.STANDARD));
            parameters.saveAndFlush(new AlertTemplateParameterDefinition(template, "credentials", "test.name", "test.description", KubeconfigCredentials.class.getName(),
                    List.of(), true, false, null, false, 1, true, List.of(AlertParameterSource.SECRET), List.of(), List.of("KUBECONFIG")));
        });
        jdbc.execute("INSERT INTO core.configurations(name,value_type,configuration_value) VALUES ('Metadata configuration','STRING','\"fixture\"'::jsonb)");
        // Synthetic ciphertext intentionally cannot be decrypted. Only metadata is consulted.
        secretId = jdbc.queryForObject("""
                INSERT INTO secrets.secrets(name,value_type,encrypted_value,encryption_iv,value_hash,hash_salt,encryption_version)
                VALUES ('Metadata kubeconfig','KUBECONFIG',decode(repeat('00',16),'hex'),decode(repeat('00',12),'hex'),
                        decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),1) RETURNING id
                """, Long.class);
    }

    @AfterAll
    void closeJpa() {
        if (factory != null)
            factory.destroy();
    }

    @Test
    void actualDerivedProjectionQueriesNeverSelectProtectedValues() {
        statements.clear();
        transactions.executeWithoutResult(status -> {
            var configurations = repositories.getRepository(ApplicationConfigurationRepository.class);
            var secrets = repositories.getRepository(ApplicationSecretRepository.class);
            var procedures = repositories.getRepository(ProcedureRepository.class);
            var pipes = repositories.getRepository(PipeRepository.class);
            assertThat(configurations.findAllProjectedBy()).hasSize(1);
            assertThat(configurations.findProjectedById(configurations.findAllProjectedBy().getFirst().getId())).isPresent();
            assertThat(secrets.findProjectedById(secretId)).isPresent();
            assertThat(procedures.findAllProjectedBy()).isEmpty();
            assertThat(procedures.findProjectedById(999L)).isEmpty();
            assertThat(pipes.findAllProjectedBy()).isEmpty();
            assertThat(pipes.findProjectedById(999L)).isEmpty();
            var candidates = service.candidates("test.MetadataOnly", "credentials", null, null);
            assertThat(candidates.candidates().content()).extracting(AiTemplateBindingCandidates.Candidate::id).containsExactly(secretId);
            var value = new AiProcedureParameterValue("credentials", AiProcedureParameterSource.SECRET, null, null, secretId, null, null);
            assertThat(service.validate("test.MetadataOnly", List.of(value)).valid()).isTrue();
        });
        List<String> secretReads = statements.stream().filter(sql -> sql.startsWith("select ") && sql.contains("from secrets.secrets ")).toList();
        List<String> configurationReads = statements.stream().filter(sql -> sql.startsWith("select ") && sql.contains("from core.configurations ")).toList();
        assertThat(secretReads).isNotEmpty().allSatisfy(sql -> assertThat(sql).doesNotContain("encrypted_value", "encryption_iv", "value_hash", "hash_salt"));
        assertThat(configurationReads).isNotEmpty().allSatisfy(sql -> assertThat(sql).doesNotContain("configuration_value"));
    }
}
