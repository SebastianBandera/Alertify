package app.alertify.ai.patch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import app.alertify.ai.api.AiPatchRequest;
import app.alertify.ai.api.AiPatchRequest.Operation;
import app.alertify.api.error.ConflictException;
import app.alertify.configuration.api.ConfigurationResponse;
import app.alertify.configuration.api.ConfigurationUpdateRequest;
import app.alertify.configuration.service.ConfigurationValueValidator;
import app.alertify.hooks.api.HookUpdateRequest;
import app.alertify.hooks.model.Hook;
import app.alertify.hooks.model.HookMode;
import app.alertify.hooks.model.HookTarget;
import app.alertify.hooks.service.HookManagementService;
import app.alertify.hooks.service.HookMapper;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.repository.*;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.pipes.api.PipeUpdateRequest;
import app.alertify.pipes.execution.PipeExecutionOrchestrator;
import app.alertify.pipes.model.Pipe;
import app.alertify.pipes.model.PipeStep;
import app.alertify.pipes.service.PipeBindingExpressionService;
import app.alertify.pipes.service.PipeManagementService;
import app.alertify.procedures.model.Procedure;
import app.alertify.procedures.model.ProcedureTemplateDefinition;
import app.alertify.services.secret.SecretEncryptionService;
import app.alertify.worker.contract.WorkerCapability;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import tools.jackson.databind.json.JsonMapper;

/** Only runs on an empty disposable PostgreSQL database. Never points to the running application. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "ALERTIFY_PATCH_TEST_DATABASE_URL", matches = "jdbc:postgresql:.+")
class AiPatchJpaIntegrationTest {
    private LocalContainerEntityManagerFactoryBean factory;
    private ValidatorFactory validators;
    private EntityManager entities;
    private TransactionTemplate transactions;
    private JpaRepositoryFactory repositories;
    private AiPatchService patches;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeAll
    void initialize() throws Exception {
        var environment = System.getenv();
        var dataSource = new DriverManagerDataSource(environment.get("ALERTIFY_PATCH_TEST_DATABASE_URL"),
                environment.getOrDefault("ALERTIFY_PATCH_TEST_DATABASE_USER", "postgres"),
                environment.getOrDefault("ALERTIFY_PATCH_TEST_DATABASE_PASSWORD", ""));
        var jdbc = new JdbcTemplate(dataSource);
        for (String schema : List.of("core", "audit", "secrets"))
            assertNull(jdbc.queryForObject("SELECT to_regnamespace(?)::text", String.class, schema));

        try (var files = Files.list(Path.of(environment.get("ALERTIFY_PATCH_TEST_MIGRATIONS")))) {
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
        entities = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        transactions = new TransactionTemplate(new JpaTransactionManager(factory.getObject()));
        repositories = new JpaRepositoryFactory(entities);
        validators = Validation.buildDefaultValidatorFactory();
        patches = new AiPatchService(new AiPatchEngine(json), json, validators.getValidator(), entities, new ConfigurationValueValidator());
    }

    @AfterAll
    void close() {
        if (factory != null)
            factory.destroy();

        if (validators != null)
            validators.close();
    }

    @Test
    void preservesUnrequestedFieldsAndNestedJson() {
        long id = configuration();
        var initial = transactions.execute(status -> read(id));
        var result = transactions.execute(status -> patchConfiguration(id, initial.version(),
                op("replace", "/value/timeout", "60")));
        assertThat(result.name()).isEqualTo(initial.name());
        assertThat(result.writable()).isFalse();
        assertThat(result.description()).isEqualTo("keep");
        assertThat(result.value().path("retry").intValue()).isEqualTo(3);
        assertThat(result.value().path("timeout").intValue()).isEqualTo(60);
        assertThat(result.version()).isGreaterThan(initial.version());
    }

    @Test
    void rollsBackTheWholeWriteWhenPersistenceDiffersFromExpected() {
        long id = configuration();
        var initial = transactions.execute(status -> read(id));
        assertThatThrownBy(() -> transactions.execute(status -> patches.patch(id,
                request(initial.version(), op("replace", "/description", "\"requested\"")),
                ApplicationConfiguration.class, ConfigurationUpdateRequest.class, () -> read(id), complete -> {
                    write(id, complete);
                    entities.find(ApplicationConfiguration.class, id).changeWritable(true);
                    return read(id);
                }))).isInstanceOf(IllegalStateException.class);
        assertThat(transactions.<ConfigurationResponse>execute(status -> read(id))).isEqualTo(initial);
    }

    @Test
    void rejectsInvalidFinalStateUnknownFieldsAndCoercionWithoutWriting() {
        long id = configuration();
        var initial = transactions.execute(status -> read(id));
        for (Operation invalid : List.of(op("replace", "/name", "\"\""), op("replace", "/writable", "\"false\""),
                op("add", "/id", "99"), op("replace", "/value", "null"))) {
            assertThatThrownBy(() -> transactions.execute(status -> patchConfiguration(id, initial.version(),
                    op("replace", "/description", "\"must-rollback\""), invalid))).isInstanceOf(IllegalArgumentException.class);
            assertThat(transactions.<ConfigurationResponse>execute(status -> read(id))).isEqualTo(initial);
        }
    }

    @Test
    void rejectsAStaleProposalAndSerializesConcurrentPatches() throws Exception {
        long id = configuration();
        long version = transactions.execute(status -> read(id).version());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> attempt(id, version, "one", start));
            var second = executor.submit(() -> attempt(id, version, "two", start));
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("COMMITTED", "CONFLICT");
        }
        assertThatThrownBy(() -> transactions.execute(status -> patchConfiguration(id, version,
                op("replace", "/name", "\"stale\"")))).isInstanceOf(ConflictException.class);
    }

    @Test
    void preservesPipeStepsAndAdvancesOwnerVersionForStepOnlyChanges() {
        long id = pipe();
        var service = pipes();
        var initial = transactions.execute(status -> service.get(id));
        var result = transactions.execute(status -> patches.patch(id,
                request(initial.version(), op("test", "/steps/0/key", "\"first\""), op("replace", "/steps/0/timeout", "\"PT2S\"")),
                Pipe.class, PipeUpdateRequest.class, () -> service.get(id), complete -> service.updatePartial(id, complete)));
        assertThat(result.steps().getFirst().id()).isEqualTo(initial.steps().getFirst().id());
        assertThat(result.steps().getFirst().timeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(result.version()).isGreaterThan(initial.version());
    }

    @Test
    void preservesHookTargetIdentityOnTargetOnlyChanges() {
        long pipeId = pipe();
        var hooks = hooks();
        long id = transactions.execute(status -> {
            var hook = new Hook("patch-hook-" + UUID.randomUUID(), "keep", HookMode.SEQUENTIAL, null, null, null, null);
            entities.persist(hook);
            hook.replaceTargets(List.of(HookTarget.pipe(hook, entities.find(Pipe.class, pipeId), 0, List.of("SUCCESS"), 1000)));
            entities.flush();
            return hook.getId();
        });
        var initial = transactions.execute(status -> hooks.get(id));
        var result = transactions.execute(status -> patches.patch(id, request(initial.version(),
                op("test", "/targets/0/type", "\"PIPE\""), op("test", "/targets/0/resourceId", Long.toString(pipeId)),
                op("replace", "/targets/0/busyWaitTimeout", "\"PT2S\"")),
                Hook.class, HookUpdateRequest.class, () -> hooks.get(id), complete -> hooks.updatePartial(id, complete)));
        assertThat(result.targets().getFirst().id()).isEqualTo(initial.targets().getFirst().id());
        assertThat(result.targets().getFirst().busyWaitTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(result.version()).isGreaterThan(initial.version());
    }

    private String attempt(long id, long version, String description, CountDownLatch start) throws Exception {
        start.await();
        try {
            transactions.execute(status -> patchConfiguration(id, version, op("replace", "/description", json.writeValueAsString(description))));
            return "COMMITTED";
        } catch (ConflictException exception) {
            return "CONFLICT";
        }
    }

    @Test
    void addsMovesAndRemovesHookTargetsWithoutReplacingRetainedTargets() {
        long firstPipe = pipe();
        long secondPipe = pipe();
        var service = hooks();
        long id = transactions.execute(status -> {
            var hook = new Hook("patch-hook-order-" + UUID.randomUUID(), null, HookMode.SEQUENTIAL, null, null, null, null);
            entities.persist(hook);
            hook.replaceTargets(List.of(HookTarget.pipe(hook, entities.find(Pipe.class, firstPipe), 0, List.of("SUCCESS"), 1000)));
            entities.flush();
            return hook.getId();
        });
        var initial = transactions.execute(status -> service.get(id));
        var added = transactions.execute(status -> patches.patch(id, request(initial.version(),
                op("add", "/targets/-", "{\"type\":\"PIPE\",\"resourceId\":" + secondPipe + ",\"continueOn\":[\"SUCCESS\"]}"),
                op("test", "/targets/0/type", "\"PIPE\""), op("test", "/targets/0/resourceId", Long.toString(firstPipe)),
                new Operation("move", "/targets/1", null, "/targets/0")),
                Hook.class, HookUpdateRequest.class, () -> service.get(id), complete -> service.updatePartial(id, complete)));
        assertThat(added.targets().getFirst().resourceId()).isEqualTo(secondPipe);
        assertThat(added.targets().get(1).id()).isEqualTo(initial.targets().getFirst().id());
        assertThat(added.targets().getFirst().busyWaitTimeout()).isEqualTo(Duration.ofMinutes(30));
        var removed = transactions.execute(status -> patches.patch(id, request(added.version(),
                op("test", "/targets/0/type", "\"PIPE\""), op("test", "/targets/0/resourceId", Long.toString(secondPipe)),
                op("remove", "/targets/0", null)), Hook.class, HookUpdateRequest.class,
                () -> service.get(id), complete -> service.updatePartial(id, complete)));
        assertThat(removed.targets()).hasSize(1);
        assertThat(removed.targets().getFirst().id()).isEqualTo(initial.targets().getFirst().id());
    }

    @Test
    void preservesBindingIdentityAcrossEditsAndStepInsertMoveRemove() {
        long id = pipe();
        transactions.executeWithoutResult(status -> {
            var pipe = entities.find(Pipe.class, id);
            var source = pipe.getSteps().getFirst();
            var resource = source.getProcedure();
            var parameter = new app.alertify.procedures.model.ProcedureTemplateParameterDefinition(resource.getTemplate(),
                    "message", "test.label", "test.description", String.class.getName(), List.of(), true, null, false, 0, false,
                    List.of(app.alertify.alerts.template.annotation.AlertParameterSource.TEXT), List.of(), List.of());
            entities.persist(parameter);
            var target = PipeStep.procedure(pipe, "consumer", 1, resource, 1000, List.of("SUCCESS"));
            target.addBinding(new app.alertify.pipes.model.PipeStepBinding(target, parameter, source, "/value", null));
            pipe.synchronizeSteps(List.of(source, target));
        });
        var service = pipes();
        var initial = transactions.execute(status -> service.get(id));
        long bindingId = transactions.execute(status -> entities.find(Pipe.class, id).getSteps().get(1).getBindings().getFirst().getId());
        long resourceId = initial.steps().getFirst().resourceId();
        var edited = transactions.execute(status -> patches.patch(id, request(initial.version(),
                op("test", "/steps/1/key", "\"consumer\""),
                op("test", "/steps/1/bindings/0/targetParameterKey", "\"message\""),
                op("replace", "/steps/1/bindings/0/valueExpression", "\"new-expression\""),
                op("add", "/steps/-", "{\"key\":\"extra\",\"type\":\"PROCEDURE\",\"resourceId\":" + resourceId + "}"),
                op("test", "/steps/2/key", "\"extra\""), new Operation("move", "/steps/0", null, "/steps/2")),
                Pipe.class, PipeUpdateRequest.class, () -> service.get(id), complete -> service.updatePartial(id, complete)));
        assertThat(edited.steps().stream().map(step -> step.key()).toList()).containsExactly("extra", "first", "consumer");
        assertThat(edited.steps().get(1).id()).isEqualTo(initial.steps().getFirst().id());
        assertThat(edited.steps().get(2).id()).isEqualTo(initial.steps().get(1).id());
        assertThat(edited.steps().getFirst().timeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(transactions.<Long>execute(status -> entities.find(Pipe.class, id).getSteps().get(2).getBindings().getFirst().getId())).isEqualTo(bindingId);
        var removed = transactions.execute(status -> patches.patch(id, request(edited.version(),
                op("test", "/steps/0/key", "\"extra\""), op("remove", "/steps/0", null)),
                Pipe.class, PipeUpdateRequest.class, () -> service.get(id), complete -> service.updatePartial(id, complete)));
        assertThat(removed.steps().stream().map(step -> step.id()).toList())
                .containsExactlyElementsOf(initial.steps().stream().map(step -> step.id()).toList());
    }

    private long configuration() {
        return transactions.execute(status -> {
            var value = new ApplicationConfiguration("patch-" + UUID.randomUUID(), "keep", ConfigurationValueType.JSON,
                    json.readTree("{\"timeout\":30,\"retry\":3}"), Set.of(), false);
            entities.persist(value);
            entities.flush();
            return value.getId();
        });
    }

    private ConfigurationResponse patchConfiguration(long id, long version, Operation... operations) {
        var service = app.alertify.configuration.service.PatchConfigurationTestServices.create(repo(ApplicationConfigurationRepository.class), repo(TagRepository.class));
        return patches.patch(id, request(version, operations), ApplicationConfiguration.class,
                ConfigurationUpdateRequest.class, () -> service.getFresh(id), complete -> service.update(id, complete));
    }

    private ConfigurationResponse read(long id) {
        var value = entities.find(ApplicationConfiguration.class, id);
        return new ConfigurationResponse(id, value.getVersion(), value.getName(), value.getDescription(), value.getValueType(),
                value.getValue(), value.isWritable(), Set.of(), value.getCreatedAt(), value.getUpdatedAt());
    }

    private ConfigurationResponse write(long id, ConfigurationUpdateRequest complete) {
        var value = entities.find(ApplicationConfiguration.class, id);
        value.rename(complete.name());
        value.changeDescription(complete.description());
        value.changeValue(complete.valueType(), complete.value());
        value.changeWritable(complete.writable());
        entities.flush();
        return read(id);
    }

    private long pipe() {
        return transactions.execute(status -> {
            var template = new ProcedureTemplateDefinition("test.Patch" + UUID.randomUUID(), "test.name", "test.description",
                    "test/source", WorkerCapability.STANDARD, false, List.of());
            entities.persist(template);
            var resource = new Procedure(template, "patch-resource-" + UUID.randomUUID(), null, "-", false, false, Set.of());
            entities.persist(resource);
            var pipe = new Pipe("patch-pipe-" + UUID.randomUUID(), "keep", false, false);
            entities.persist(pipe);
            pipe.replaceSteps(List.of(PipeStep.procedure(pipe, "first", 0, resource, 1000, List.of("SUCCESS"))));
            entities.flush();
            return pipe.getId();
        });
    }

    private PipeManagementService pipes() {
        return new PipeManagementService(repo(PipeRepository.class), mock(PipeExecutionRepository.class), repo(AlertRepository.class),
                repo(AlertTemplateParameterDefinitionRepository.class), repo(ProcedureRepository.class), repo(ProcedureTemplateParameterDefinitionRepository.class),
                repo(ProcedureTemplateOutputDefinitionRepository.class), mock(HookRepository.class), repo(ProcedureParameterValueRepository.class),
                repo(TagRepository.class), mock(PipeExecutionOrchestrator.class), mock(ApplicationEventLogger.class), mock(PipeBindingExpressionService.class));
    }

    private HookManagementService hooks() {
        return new HookManagementService(repo(HookRepository.class), repo(AlertRepository.class), repo(ProcedureRepository.class),
                repo(PipeRepository.class), repo(ApplicationSecretRepository.class), mock(HookInvocationRepository.class),
                mock(HookInvocationTargetRepository.class), repo(TagRepository.class), mock(SecretEncryptionService.class),
                new HookMapper(), mock(ApplicationEventLogger.class));
    }

    private <T> T repo(Class<T> type) { return repositories.getRepository(type); }
    private static AiPatchRequest request(long version, Operation... operations) { return new AiPatchRequest(version, List.of(operations)); }
    private static Operation op(String op, String path, String value) { return new Operation(op, path, value, null); }
}
