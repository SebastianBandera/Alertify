package app.alertify.alerts.service;

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
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import app.alertify.alerts.api.AlertCreateRequest;
import app.alertify.alerts.api.AlertParameterValueRequest;
import app.alertify.alerts.api.AlertResponse;
import app.alertify.alerts.api.AlertUpdateRequest;
import app.alertify.alerts.execution.AlertExecutionOrchestrator;
import app.alertify.alerts.execution.AlertScheduleService;
import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.alerts.model.AlertTemplateParameterDefinition;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.api.error.ConflictException;
import app.alertify.api.error.InvalidAlertRequestException;
import app.alertify.dashboard.DashboardEventPublisher;
import app.alertify.jpa.repository.AlertExecutionRepository;
import app.alertify.jpa.repository.AlertParameterValueRepository;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.AlertStateRepository;
import app.alertify.jpa.repository.AlertTemplateDefinitionRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.worker.contract.WorkerCapability;
import jakarta.persistence.EntityManager;

/** Runs only against an empty disposable PostgreSQL database, never the application database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "ALERTIFY_ALERT_TEST_DATABASE_URL", matches = "jdbc:postgresql:.+"),
        @EnabledIfEnvironmentVariable(named = "ALERTIFY_ALERT_TEST_MIGRATIONS", matches = ".+")
})
class AlertDefinitionVersionJpaIntegrationTest {

    private LocalContainerEntityManagerFactoryBean factory;
    private TransactionTemplate transactions;
    private EntityManager entityManager;
    private AlertManagementService service;
    private long templateId;
    private long configurationId;
    private long secretId;
    private JdbcTemplate jdbc;
    private JpaRepositoryFactory repositories;

    @BeforeAll
    void initializeDisposableDatabase() throws Exception {
        var environment = System.getenv();
        var dataSource = new DriverManagerDataSource(environment.get("ALERTIFY_ALERT_TEST_DATABASE_URL"),
                environment.getOrDefault("ALERTIFY_ALERT_TEST_DATABASE_USER", "postgres"),
                environment.getOrDefault("ALERTIFY_ALERT_TEST_DATABASE_PASSWORD", ""));
        jdbc = new JdbcTemplate(dataSource);
        for (String schema : List.of("core", "audit", "secrets"))
            assertNull(jdbc.queryForObject("SELECT to_regnamespace(?)::text", String.class, schema));

        try (var files = Files.list(Path.of(environment.get("ALERTIFY_ALERT_TEST_MIGRATIONS")))) {
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
        repositories = new JpaRepositoryFactory(entityManager);
        repositories.addRepositoryProxyPostProcessor((proxy, _) ->
                proxy.addAdvice(new PersistenceExceptionTranslationInterceptor(new HibernateJpaDialect())));
        var alerts = repositories.getRepository(AlertRepository.class);
        var templates = repositories.getRepository(AlertTemplateDefinitionRepository.class);
        var definitions = repositories.getRepository(AlertTemplateParameterDefinitionRepository.class);
        var parameters = repositories.getRepository(AlertParameterValueRepository.class);
        service = new AlertManagementService(alerts, templates, definitions, parameters,
                mock(AlertExecutionRepository.class), mock(AlertStateRepository.class),
                repositories.getRepository(ApplicationConfigurationRepository.class),
                repositories.getRepository(ApplicationSecretRepository.class),
                repositories.getRepository(ProcedureRepository.class), repositories.getRepository(TagRepository.class),
                mock(ApplicationEventLogger.class), mock(AlertScheduleService.class),
                mock(AlertExecutionOrchestrator.class), mock(DashboardEventPublisher.class),
                mock(ResourceResultObserverService.class), entityManager);
        templateId = transactions.execute(status -> {
            var template = templates.saveAndFlush(new AlertTemplateDefinition("test.AlertDefinitionVersion",
                    "test.name", "test.description", "test/source", WorkerCapability.STANDARD));
            for (int index = 0; index < 2; index++)
                definitions.save(new AlertTemplateParameterDefinition(template, index == 0 ? "message" : "second",
                        "test.parameter", "test.description", "java.lang.String", List.of(), true, null, false,
                        index, index == 0, List.of(AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION,
                                AlertParameterSource.SECRET), List.of(), List.of()));

            definitions.flush();
            return template.getId();
        });
        configurationId = jdbc.queryForObject("""
                INSERT INTO core.configurations(name, value_type, configuration_value)
                VALUES ('Alert definition version configuration', 'STRING', '"fixture"'::jsonb) RETURNING id
                """, Long.class);
        // Metadata-only synthetic ciphertext: never decrypted or sent to a worker.
        secretId = jdbc.queryForObject("""
                INSERT INTO secrets.secrets(name, value_type, encrypted_value, encryption_iv, value_hash,
                        hash_salt, encryption_version)
                VALUES ('Alert definition version secret', 'STRING', decode(repeat('00',16),'hex'),
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
    void rejectsPendingDescriptionSnapshotAfterMessageOnlyUpdate() {
        var initial = fixture();
        var pending = request(initial, "Description A", List.of(text("message", "QA-INITIAL"), text("second", "SECOND")));
        var changed = update(initial.id(), request(initial, initial.description(), List.of(text("message", "QA-VALUE-B"), text("second", "SECOND"))));
        assertThat(changed.version()).isEqualTo(initial.version() + 1);
        assertThat(changed.parameters().getFirst().version()).isEqualTo(initial.parameters().getFirst().version() + 1);
        assertThat(read(initial.id())).isEqualTo(changed);

        assertThatThrownBy(() -> update(initial.id(), pending)).isInstanceOf(ConflictException.class);
        assertThat(read(initial.id())).isEqualTo(changed);
        assertThat(read(initial.id()).parameters().getFirst().textValue()).isEqualTo("QA-VALUE-B");
    }

    @Test
    void bindingChangesAdditionsAndRemovalsAdvanceOwnerVersion() {
        var initial = fixture();
        var configured = update(initial.id(), request(initial, initial.description(), List.of(
                new AlertParameterValueRequest("message", AlertParameterSource.CONFIGURATION, null, configurationId, null))));
        assertThat(configured.version()).isEqualTo(initial.version() + 1);
        assertThat(configured.parameters()).hasSize(1);
        assertThat(configured.parameters().getFirst().configurationId()).isEqualTo(configurationId);
        var secret = update(initial.id(), request(configured, configured.description(), List.of(
                new AlertParameterValueRequest("message", AlertParameterSource.SECRET, null, null, secretId))));
        assertThat(secret.version()).isEqualTo(configured.version() + 1);
        assertThat(secret.parameters().getFirst().secretId()).isEqualTo(secretId);
        var added = update(initial.id(), request(secret, secret.description(), List.of(text("message", "RESTORED"), text("second", "ADDED"))));
        assertThat(added.version()).isEqualTo(secret.version() + 1);
        assertThat(added.parameters()).hasSize(2);
        assertThat(read(initial.id())).isEqualTo(added);
        assertThatThrownBy(() -> update(initial.id(), request(configured, "Stale", List.of(text("message", "OLD")))))
                .isInstanceOf(ConflictException.class);
        assertThat(read(initial.id())).isEqualTo(added);
    }

    @Test
    void invalidParameterRollsBackDefinitionAndEarlierParameterChanges() {
        var initial = fixture();
        assertThatThrownBy(() -> update(initial.id(), request(initial, "Invalid description", List.of(
                text("message", "SHOULD-ROLL-BACK"), text("second", null)))))
                .isInstanceOf(InvalidAlertRequestException.class);
        assertThat(read(initial.id())).isEqualTo(initial);
    }

    @Test
    void parentFieldUpdateReturnsCommittedVersionWithoutDoubleIncrement() {
        var initial = fixture();
        var changed = update(initial.id(), request(initial, "Changed description", List.of(text("message", "QA-INITIAL"), text("second", "SECOND"))));
        assertThat(changed.version()).isEqualTo(initial.version() + 1);
        assertThat(read(initial.id())).isEqualTo(changed);
        assertThat(changed.parameters()).isEqualTo(initial.parameters());
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> service.delete(initial.id(), initial.version())))
                .isInstanceOf(ConflictException.class);
        assertThat(read(initial.id())).isEqualTo(changed);
    }

    @Test
    void acceptedUnchangedDefinitionStillAdvancesTheVersion() {
        var initial = fixture();
        var changed = update(initial.id(), request(initial, initial.description(), List.of(text("message", "QA-INITIAL"), text("second", "SECOND"))));
        assertThat(changed.version()).isEqualTo(initial.version() + 1);
        assertThat(changed.parameters()).isEqualTo(initial.parameters());
        assertThat(read(initial.id())).isEqualTo(changed);
    }

    @Test
    void overlappingParameterOnlyTransactionRollsBackWhenOwnerVersionChangesAfterRead() throws Exception {
        concurrentUpdate(false, false);
    }

    @Test
    void overlappingParentUpdateRollsBackWhenOwnerVersionChangesAfterRead() throws Exception {
        concurrentUpdate(true, false);
    }

    @Test
    void overlappingCachedParameterUpdateRollsBackWhenChildVersionChangesAfterRead() throws Exception {
        concurrentUpdate(false, true);
    }

    private void concurrentUpdate(boolean changeDescription, boolean cacheParameters) throws Exception {
        var initial = fixture();
        var loaded = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> {
                assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    assertThat(entityManager.find(Alert.class, initial.id()).getVersion()).isEqualTo(initial.version());
                    if (cacheParameters)
                        assertThat(service.get(initial.id())).isEqualTo(initial);

                    loaded.countDown();
                    await(committed);
                    service.update(initial.id(), request(initial, changeDescription ? "Stale description" : initial.description(),
                            List.of(text("message", cacheParameters ? "QA-VALUE-A" : "QA-INITIAL"), text("second", "SECOND-A"))));
                })).isInstanceOfAny(ConflictException.class, ObjectOptimisticLockingFailureException.class);
            });
            try {
                await(loaded);
                var changed = update(initial.id(), request(initial, initial.description(), List.of(text("message", "QA-VALUE-B"), text("second", "SECOND"))));
                assertThat(changed.version()).isEqualTo(initial.version() + 1);
            } finally {
                committed.countDown();
            }
            pending.get(30, TimeUnit.SECONDS);
        }
        var after = read(initial.id());
        assertThat(after.description()).isEqualTo(initial.description());
        assertThat(after.version()).isEqualTo(initial.version() + 1);
        assertThat(after.parameters().getFirst().textValue()).isEqualTo("QA-VALUE-B");
        assertThat(after.parameters().getFirst().version()).isEqualTo(initial.parameters().getFirst().version() + 1);
        assertThat(after.parameters().getLast()).isEqualTo(initial.parameters().getLast());
    }

    @Test
    void smartIntervalsResolveLiveValuesAndPersistOneFailurePerIncidentAcrossResolverRestarts() {
        Long configId = jdbc.queryForObject("insert into core.configurations(name, value_type, configuration_value) values (?, 'INTEGER', '6'::jsonb) returning id",
                Long.class, "Smart interval " + UUID.randomUUID());
        var first = fixture();
        var second = fixture();
        var other = fixture();
        Long otherConfig = jdbc.queryForObject("insert into core.configurations(name, value_type, configuration_value) values (?, 'INTEGER', '2'::jsonb) returning id",
                Long.class, "Other smart interval " + UUID.randomUUID());
        for (var initial : List.of(first, second, other)) {
            Long selected = initial.id().equals(other.id()) ? otherConfig : configId;
            update(initial.id(), new AlertUpdateRequest(initial.version(), initial.name(), initial.description(), "-", true, false,
                    List.of(text("message", "fixture")), Set.of(), false, true, null,
                    app.alertify.alerts.model.SmartExecutionPolicy.ONCE_PER_INTERVAL,
                    app.alertify.alerts.model.SmartExecutionIntervalSource.CONFIGURATION, selected));
        }
        var alerts = repositories.getRepository(AlertRepository.class);
        var executions = repositories.getRepository(AlertExecutionRepository.class);
        var dashboard = mock(DashboardEventPublisher.class);
        var persistence = new app.alertify.alerts.execution.AlertExecutionPersistenceService(alerts, executions,
                mock(AlertStateRepository.class), mock(ApplicationEventLogger.class), tools.jackson.databind.json.JsonMapper.builder().build(),
                mock(app.alertify.configuration.service.WritableConfigurationService.class), mock(app.alertify.services.secret.WritableSecretService.class));
        java.util.function.Supplier<app.alertify.alerts.execution.SmartExecutionIntervalResolver> restart = () ->
                new app.alertify.alerts.execution.SmartExecutionIntervalResolver(repositories.getRepository(ApplicationConfigurationRepository.class), jdbc, persistence, dashboard);
        java.util.function.Function<Long, Integer> resolve = id -> transactions.execute(status -> restart.get().resolve(alerts.findById(id).orElseThrow()));
        assertThat(resolve.apply(first.id())).isEqualTo(6);
        assertThat(resolve.apply(second.id())).isEqualTo(6);
        assertThat(resolve.apply(other.id())).isEqualTo(2);
        var usages = transactions.execute(status -> new app.alertify.configuration.service.ConfigurationUsageService(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),
                repositories.getRepository(ApplicationConfigurationRepository.class)).get(configId));
        assertThat(usages.direct()).extracting(usage -> usage.id()).containsExactlyInAnyOrder(first.id(), second.id());
        jdbc.update("update core.configurations set configuration_value = '2'::jsonb where id = ?", configId);
        assertThat(resolve.apply(first.id())).isEqualTo(2);
        assertThat(resolve.apply(second.id())).isEqualTo(2);
        for (String value : List.of("0", "-7")) {
            jdbc.update("update core.configurations set configuration_value = ?::jsonb where id = ?", value, configId);
            assertThat(resolve.apply(first.id())).isEqualTo(1);
        }
        jdbc.update("update core.configurations set configuration_value = '99999999999999999999999999999'::jsonb where id = ?", configId);
        assertThat(resolve.apply(first.id())).isEqualTo(Integer.MAX_VALUE);
        assertThat(executions.latestFinishedAtByAlertAndStatus(first.id(), app.alertify.alerts.execution.AlertExecutionStatus.SUCCESS)).isEmpty();
        jdbc.update("update core.configurations set value_type = 'STRING', configuration_value = '\"hidden\"'::jsonb where id = ?", configId);
        assertThat(resolve.apply(first.id())).isNull();
        assertThat(resolve.apply(first.id())).isNull();
        assertThat(executions.countByAlert_Id(first.id())).isEqualTo(1);
        var failed = jdbc.queryForMap("select status, trigger, worker_name, error_message from core.alert_executions where alert_id = ?", first.id());
        assertThat(failed.get("status")).isEqualTo("ERROR");
        assertThat(failed.get("trigger")).isEqualTo("SMART");
        assertThat(failed.get("worker_name")).isNull();
        assertThat(failed.get("error_message").toString()).contains("INTEGER").doesNotContain("hidden");
        org.mockito.Mockito.verify(dashboard).alertChangedAfterCommit(first.id());
        // Rolling back must not consume the incident or leave a history row.
        jdbc.update("update core.configurations set value_type = 'INTEGER', configuration_value = '6'::jsonb where id = ?", configId);
        assertThat(resolve.apply(first.id())).isEqualTo(6);
        jdbc.update("update core.configurations set value_type = 'STRING', configuration_value = '\"invalid\"'::jsonb where id = ?", configId);
        transactions.executeWithoutResult(status -> {
            restart.get().resolve(alerts.findById(first.id()).orElseThrow());
            status.setRollbackOnly();
        });
        assertThat(executions.countByAlert_Id(first.id())).isEqualTo(1);
        assertThat(resolve.apply(first.id())).isNull();
        assertThat(executions.countByAlert_Id(first.id())).isEqualTo(2);
        jdbc.update("delete from core.configurations where id = ?", configId);
        assertThat(read(first.id()).smartExecutionIntervalSource()).isEqualTo(app.alertify.alerts.model.SmartExecutionIntervalSource.CONFIGURATION);
        assertThat(read(first.id()).smartExecutionIntervalConfigurationId()).isNull();
        assertThat(resolve.apply(first.id())).isNull();
        assertThat(resolve.apply(first.id())).isNull();
        assertThat(executions.countByAlert_Id(first.id())).isEqualTo(3);
        assertThat(resolve.apply(other.id())).isEqualTo(2);
    }

    private AlertResponse fixture() {
        return transactions.execute(status -> service.create(new AlertCreateRequest(templateId,
                "Alert definition version " + UUID.randomUUID(), "Initial description", "-", false, false,
                List.of(text("message", "QA-INITIAL"), text("second", "SECOND")), Set.of(), false,
                false, null, null)));
    }

    private AlertResponse update(long id, AlertUpdateRequest request) {
        return transactions.execute(status -> service.update(id, request));
    }

    private AlertResponse read(long id) {
        return transactions.execute(status -> service.get(id));
    }

    private static AlertUpdateRequest request(AlertResponse snapshot, String description, List<AlertParameterValueRequest> parameters) {
        return new AlertUpdateRequest(snapshot.version(), snapshot.name(), description, snapshot.cronExpression(),
                snapshot.enabled(), snapshot.allowConcurrentExecutions(), parameters, Set.of(), snapshot.persistentIssues(),
                snapshot.smartExecutionEnabled(), snapshot.smartExecutionIntervalHours(), snapshot.smartExecutionPolicy());
    }

    private static AlertParameterValueRequest text(String key, String value) {
        return new AlertParameterValueRequest(key, AlertParameterSource.TEXT, value, null, null);
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
