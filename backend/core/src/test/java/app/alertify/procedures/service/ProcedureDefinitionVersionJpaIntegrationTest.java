package app.alertify.procedures.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.springframework.dao.support.PersistenceExceptionTranslationInterceptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.support.TransactionTemplate;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.api.error.ConflictException;
import app.alertify.api.error.InvalidProcedureRequestException;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.ai.api.AiProcedureParameterSource;
import app.alertify.ai.api.AiProcedureParameterValue;
import app.alertify.ai.api.AiProcedureUpdateRequest;
import app.alertify.jpa.repository.AlertParameterValueRepository;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.PipeRepository;
import app.alertify.jpa.repository.ProcedureExecutionRepository;
import app.alertify.jpa.repository.ProcedureParameterValueRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.jpa.repository.ProcedureTemplateDefinitionRepository;
import app.alertify.jpa.repository.ProcedureTemplateParameterDefinitionRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.procedures.api.ProcedureCreateRequest;
import app.alertify.procedures.api.ProcedureParameterValueRequest;
import app.alertify.procedures.api.ProcedureResponse;
import app.alertify.procedures.api.ProcedureUpdateRequest;
import app.alertify.procedures.execution.ProcedureExecutionOrchestrator;
import app.alertify.procedures.execution.ProcedureScheduleService;
import app.alertify.procedures.model.Procedure;
import app.alertify.procedures.model.ProcedureTemplateDefinition;
import app.alertify.procedures.model.ProcedureTemplateParameterDefinition;
import app.alertify.worker.contract.WorkerCapability;
import jakarta.persistence.EntityManager;

/** Runs only against an empty disposable PostgreSQL database, never the application database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "ALERTIFY_PROCEDURE_TEST_DATABASE_URL", matches = "jdbc:postgresql:.+"),
        @EnabledIfEnvironmentVariable(named = "ALERTIFY_PROCEDURE_TEST_MIGRATIONS", matches = ".+")
})
class ProcedureDefinitionVersionJpaIntegrationTest {

    private LocalContainerEntityManagerFactoryBean factory;
    private TransactionTemplate transactions;
    private EntityManager entityManager;
    private ProcedureManagementService service;
    private long templateId;
    private long configurationId;
    private long secretId;

    @BeforeAll
    void initializeDisposableDatabase() throws Exception {
        var environment = System.getenv();
        var dataSource = new DriverManagerDataSource(environment.get("ALERTIFY_PROCEDURE_TEST_DATABASE_URL"),
                environment.getOrDefault("ALERTIFY_PROCEDURE_TEST_DATABASE_USER", "postgres"),
                environment.getOrDefault("ALERTIFY_PROCEDURE_TEST_DATABASE_PASSWORD", ""));
        var jdbc = new JdbcTemplate(dataSource);
        for (String schema : List.of("core", "audit", "secrets"))
            assertNull(jdbc.queryForObject("SELECT to_regnamespace(?)::text", String.class, schema));

        try (var files = Files.list(Path.of(environment.get("ALERTIFY_PROCEDURE_TEST_MIGRATIONS")))) {
            for (Path migration : files.filter(path -> path.getFileName().toString().matches("[0-9]+\\..*\\.sql"))
                    .sorted(Comparator.comparingInt(path -> Integer.parseInt(path.getFileName().toString().split("\\.")[0]))).toList())
                jdbc.execute(Files.readString(migration));

        }
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setPackagesToScan("app.alertify");
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.jdbc.time_zone", "UTC"));
        factory.afterPropertiesSet();
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        transactions = new TransactionTemplate(new JpaTransactionManager(factory.getObject()));
        var repositories = new JpaRepositoryFactory(entityManager);
        // Match the exception translation installed by Spring Data in the application.
        repositories.addRepositoryProxyPostProcessor((proxy, _) ->
                proxy.addAdvice(new PersistenceExceptionTranslationInterceptor(new HibernateJpaDialect())));
        var procedures = repositories.getRepository(ProcedureRepository.class);
        var templates = repositories.getRepository(ProcedureTemplateDefinitionRepository.class);
        var definitions = repositories.getRepository(ProcedureTemplateParameterDefinitionRepository.class);
        var parameters = repositories.getRepository(ProcedureParameterValueRepository.class);
        service = new ProcedureManagementService(procedures, templates, definitions, parameters,
                mock(ProcedureExecutionRepository.class), mock(AlertParameterValueRepository.class),
                repositories.getRepository(ApplicationConfigurationRepository.class),
                repositories.getRepository(ApplicationSecretRepository.class),
                repositories.getRepository(TagRepository.class), repositories.getRepository(PipeRepository.class),
                mock(ApplicationEventLogger.class), mock(ProcedureExecutionOrchestrator.class),
                mock(ProcedureScheduleService.class), entityManager);
        templateId = transactions.execute(status -> {
            var template = templates.saveAndFlush(new ProcedureTemplateDefinition("test.DefinitionVersion", "test.name",
                    "test.description", "test/source", WorkerCapability.STANDARD, false, List.of()));
            for (int index = 0; index < 2; index++)
                definitions.save(new ProcedureTemplateParameterDefinition(template, index == 0 ? "value" : "second",
                        "test.parameter", "test.description", "java.lang.String", List.of(), true, null, false,
                        index, index == 0, List.of(AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION,
                                AlertParameterSource.SECRET), List.of(), List.of()));

            definitions.flush();
            return template.getId();
        });
        configurationId = jdbc.queryForObject("""
                INSERT INTO core.configurations(name, value_type, configuration_value)
                VALUES ('Definition version configuration', 'STRING', '"fixture"'::jsonb) RETURNING id
                """, Long.class);
        // Metadata-only synthetic ciphertext: it is never decrypted or sent to a worker.
        secretId = jdbc.queryForObject("""
                INSERT INTO secrets.secrets(name, value_type, encrypted_value, encryption_iv, value_hash,
                        hash_salt, encryption_version)
                VALUES ('Definition version secret', 'STRING', decode(repeat('00',16),'hex'),
                        decode(repeat('00',12),'hex'), decode(repeat('00',32),'hex'),
                        decode(repeat('00',16),'hex'), 1) RETURNING id
                """, Long.class);
    }

    @AfterAll
    void closeJpa() {
        if (factory != null)
            factory.destroy();
    }

    @Test
    void rejectsPendingDescriptionSnapshotAfterParameterOnlyUpdate() {
        var initial = fixture();
        var pending = request(initial, "Description A", List.of(text("value", "INITIAL"), text("second", "SECOND")));
        var changed = update(initial.id(), request(initial, initial.description(), List.of(text("value", "VALUE-B"), text("second", "SECOND"))));
        assertThat(changed.version()).isEqualTo(initial.version() + 1);
        assertThat(read(initial.id()).version()).isEqualTo(changed.version());

        assertThatThrownBy(() -> update(initial.id(), pending)).isInstanceOf(ConflictException.class);
        var after = read(initial.id());
        assertThat(after.description()).isEqualTo(initial.description());
        assertThat(after.parameters().getFirst().textValue()).isEqualTo("VALUE-B");
        assertThat(after.version()).isEqualTo(changed.version());
    }

    @Test
    void sourceChangesAndParameterRemovalAndAdditionAdvanceDefinitionVersion() {
        var initial = fixture();
        var configured = update(initial.id(), request(initial, initial.description(), List.of(
                new ProcedureParameterValueRequest("value", AlertParameterSource.CONFIGURATION, null, configurationId, null, null, null),
                text("second", "SECOND"))));
        assertThat(configured.version()).isEqualTo(initial.version() + 1);
        var secret = update(initial.id(), request(configured, configured.description(), List.of(
                new ProcedureParameterValueRequest("value", AlertParameterSource.SECRET, null, null, secretId, null, null))));
        assertThat(secret.version()).isEqualTo(configured.version() + 1);
        assertThat(secret.parameters()).hasSize(1);
        assertThat(secret.parameters().getFirst().secretId()).isEqualTo(secretId);
        var added = update(initial.id(), request(secret, secret.description(), List.of(text("value", "RESTORED"), text("second", "ADDED"))));
        assertThat(added.version()).isEqualTo(secret.version() + 1);
        assertThat(added.parameters()).hasSize(2);
        assertThat(read(initial.id()).version()).isEqualTo(added.version());
        assertThatThrownBy(() -> update(initial.id(), request(configured, "Stale", List.of(text("value", "OLD")))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void invalidParameterRollsBackDefinitionAndEarlierParameterChanges() {
        var initial = fixture();
        assertThatThrownBy(() -> update(initial.id(), request(initial, "Invalid description", List.of(
                text("value", "SHOULD-ROLL-BACK"), text("second", null)))))
                .isInstanceOf(InvalidProcedureRequestException.class);
        var after = read(initial.id());
        assertThat(after.version()).isEqualTo(initial.version());
        assertThat(after.description()).isEqualTo(initial.description());
        assertThat(after.parameters().getFirst().textValue()).isEqualTo("INITIAL");
    }

    @Test
    void duplicateParameterKeysRejectTheUpdateWithoutChangingTheDefinition() {
        var initial = fixture();
        assertThatThrownBy(() -> update(initial.id(), request(initial, "Duplicate proposal", List.of(
                text("value", "CHANGED"), text("value", "DUPLICATE"), text("second", "SECOND")))))
                .isInstanceOf(InvalidProcedureRequestException.class).hasMessageContaining("provided more than once");
        assertThat(read(initial.id())).isEqualTo(initial);
    }

    @Test
    void missingReferenceAndDisallowedSourceRejectTheUpdateWithoutChangingTheDefinition() {
        var initial = fixture();
        var missing = new ProcedureParameterValueRequest("value", AlertParameterSource.CONFIGURATION,
                null, Long.MAX_VALUE, null, null, null);
        assertThatThrownBy(() -> update(initial.id(), request(initial, "Missing reference", List.of(
                missing, text("second", "CHANGED")))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(read(initial.id())).isEqualTo(initial);
        var disallowed = new ProcedureParameterValueRequest("value", AlertParameterSource.PIPE,
                null, null, null, null, Long.MAX_VALUE);
        assertThatThrownBy(() -> update(initial.id(), request(initial, "Wrong source", List.of(
                disallowed, text("second", "CHANGED")))))
                .isInstanceOf(InvalidProcedureRequestException.class).hasMessageContaining("not allowed");
        assertThat(read(initial.id())).isEqualTo(initial);
    }

    @Test
    void aiSourceChangesWithNullInactiveFieldsPreserveTheOtherParameter() {
        var snapshot = fixture();
        var bindings = List.of(
                new AiProcedureParameterValue("value", AiProcedureParameterSource.CONFIGURATION, null, configurationId, null, null, null),
                new AiProcedureParameterValue("value", AiProcedureParameterSource.SECRET, null, null, secretId, null, null),
                new AiProcedureParameterValue("value", AiProcedureParameterSource.TEXT, "RESTORED", null, null, null, null));
        for (var binding : bindings) {
            var ai = new AiProcedureUpdateRequest(snapshot.version(), snapshot.name(), snapshot.description(),
                    snapshot.cronExpression(), snapshot.enabled(), snapshot.allowConcurrentExecutions(), List.of(
                            binding, new AiProcedureParameterValue("second", AiProcedureParameterSource.TEXT, "SECOND", null, null, null, null)), Set.of());
            var changed = update(snapshot.id(), ai.toRequest());
            assertThat(changed.parameters()).hasSize(2);
            assertThat(changed.parameters()).extracting(value -> value.parameterKey()).doesNotHaveDuplicates();
            assertThat(changed.parameters().getFirst().source()).isEqualTo(binding.toRequest().source());
            assertThat(changed.parameters().getFirst().configurationId()).isEqualTo(binding.configurationId());
            assertThat(changed.parameters().getFirst().secretId()).isEqualTo(binding.secretId());
            assertThat(changed.parameters().getFirst().textValue()).isEqualTo(binding.textValue());
            assertThat(changed.parameters().getLast()).isEqualTo(snapshot.parameters().getLast());
            assertThat(changed.description()).isEqualTo(snapshot.description());
            assertThat(read(snapshot.id())).isEqualTo(changed);
            snapshot = changed;
        }
    }

    @Test
    void parentFieldUpdateReturnsCommittedVersionAndPreservesParameters() {
        var initial = fixture();
        var changed = update(initial.id(), request(initial, "Changed description", List.of(text("value", "INITIAL"), text("second", "SECOND"))));
        assertThat(changed.version()).isEqualTo(initial.version() + 1);
        assertThat(read(initial.id()).version()).isEqualTo(changed.version());
        assertThat(changed.parameters().getFirst().textValue()).isEqualTo("INITIAL");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> service.delete(initial.id(), initial.version())))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void overlappingParameterOnlyTransactionRollsBackWhenOwnerVersionChangesAfterRead() throws Exception {
        concurrentUpdate(false);
    }

    @Test
    void overlappingParentUpdateRollsBackWhenOwnerVersionChangesAfterRead() throws Exception {
        concurrentUpdate(true);
    }

    private void concurrentUpdate(boolean changeDescription) throws Exception {
        var initial = fixture();
        var loaded = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> {
                assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    assertThat(entityManager.find(Procedure.class, initial.id()).getVersion()).isEqualTo(initial.version());
                    loaded.countDown();
                    await(committed);
                    service.update(initial.id(), request(initial, changeDescription ? "Stale description" : initial.description(),
                            List.of(text("value", "INITIAL"), text("second", "SECOND-A"))));
                })).isInstanceOfAny(ConflictException.class, ObjectOptimisticLockingFailureException.class);
            });
            try {
                await(loaded);
                var changed = update(initial.id(), request(initial, initial.description(), List.of(text("value", "VALUE-B"), text("second", "SECOND"))));
                assertThat(changed.version()).isEqualTo(initial.version() + 1);
            } finally {
                committed.countDown();
            }
            pending.get(30, TimeUnit.SECONDS);
        }
        var after = read(initial.id());
        assertThat(after.description()).isEqualTo(initial.description());
        assertThat(after.version()).isEqualTo(initial.version() + 1);
        assertThat(after.parameters().getFirst().textValue()).isEqualTo("VALUE-B");
        assertThat(after.parameters().getLast().textValue()).isEqualTo("SECOND");
    }

    private ProcedureResponse fixture() {
        return transactions.execute(status -> service.create(new ProcedureCreateRequest(templateId,
                "Definition version " + UUID.randomUUID(), "Initial description", "-", false, false,
                List.of(text("value", "INITIAL"), text("second", "SECOND")), Set.of())));
    }

    private ProcedureResponse update(long id, ProcedureUpdateRequest request) {
        return transactions.execute(status -> service.update(id, request));
    }

    private ProcedureResponse read(long id) {
        return transactions.execute(status -> service.search(null, templateId, Set.of(), false, PageRequest.of(0, 100))
                .stream().filter(procedure -> procedure.id() == id).findFirst().orElseThrow());
    }

    private static ProcedureUpdateRequest request(ProcedureResponse snapshot, String description, List<ProcedureParameterValueRequest> parameters) {
        return new ProcedureUpdateRequest(snapshot.version(), snapshot.name(), description, snapshot.cronExpression(),
                snapshot.enabled(), snapshot.allowConcurrentExecutions(), parameters, Set.of());
    }

    private static ProcedureParameterValueRequest text(String key, String value) {
        return new ProcedureParameterValueRequest(key, AlertParameterSource.TEXT, value, null, null, null, null);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted concurrency test", exception);
        }
    }
}
