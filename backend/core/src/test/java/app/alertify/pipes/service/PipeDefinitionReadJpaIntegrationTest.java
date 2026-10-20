package app.alertify.pipes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.jpa.entity.Tag;
import app.alertify.jpa.entity.TagScope;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.AlertTemplateParameterDefinitionRepository;
import app.alertify.jpa.repository.HookRepository;
import app.alertify.jpa.repository.PipeExecutionRepository;
import app.alertify.jpa.repository.PipeRepository;
import app.alertify.jpa.repository.PipeStepResultRepository;
import app.alertify.jpa.repository.ProcedureParameterValueRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.jpa.repository.ProcedureTemplateOutputDefinitionRepository;
import app.alertify.jpa.repository.ProcedureTemplateParameterDefinitionRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.pipes.api.PipeResponse;
import app.alertify.pipes.api.PipeStepResponse;
import app.alertify.pipes.execution.PipeExecutionOrchestrator;
import app.alertify.pipes.execution.PipeExecutionPersistenceService;
import app.alertify.pipes.model.Pipe;
import app.alertify.pipes.model.PipeStep;
import app.alertify.pipes.model.PipeStepBinding;
import app.alertify.procedures.artifact.ProcedureArtifactInput;
import app.alertify.procedures.model.Procedure;
import app.alertify.procedures.model.ProcedureTemplateDefinition;
import app.alertify.procedures.model.ProcedureTemplateOutputDefinition;
import app.alertify.procedures.model.ProcedureTemplateParameterDefinition;
import app.alertify.worker.contract.WorkerCapability;
import jakarta.persistence.EntityManager;

/** Requires a new disposable database. Does not execute pipes or access application data. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "ALERTIFY_PIPE_TEST_DATABASE_URL", matches = "jdbc:postgresql:.+"),
        @EnabledIfEnvironmentVariable(named = "ALERTIFY_PIPE_TEST_MIGRATIONS", matches = ".+")
})
class PipeDefinitionReadJpaIntegrationTest {

    private LocalContainerEntityManagerFactoryBean factory;
    private EntityManager entityManager;
    private TransactionTemplate transactions;
    private PipeRepository pipes;
    private PipeManagementService service;
    private PipeExecutionPersistenceService persistence;
    private JdbcTemplate jdbc;

    @BeforeAll
    void initializeDisposableDatabase() throws Exception {
        var environment = System.getenv();
        var dataSource = new DriverManagerDataSource(environment.get("ALERTIFY_PIPE_TEST_DATABASE_URL"),
                environment.getOrDefault("ALERTIFY_PIPE_TEST_DATABASE_USER", "postgres"),
                environment.getOrDefault("ALERTIFY_PIPE_TEST_DATABASE_PASSWORD", ""));
        jdbc = new JdbcTemplate(dataSource);
        for (String schema : List.of("core", "audit", "secrets"))
            assertNull(jdbc.queryForObject("SELECT to_regnamespace(?)::text", String.class, schema));

        try (var files = Files.list(Path.of(environment.get("ALERTIFY_PIPE_TEST_MIGRATIONS")))) {
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
        pipes = repositories.getRepository(PipeRepository.class);
        service = new PipeManagementService(pipes, mock(PipeExecutionRepository.class), mock(AlertRepository.class),
                mock(AlertTemplateParameterDefinitionRepository.class), mock(ProcedureRepository.class),
                mock(ProcedureTemplateParameterDefinitionRepository.class), mock(ProcedureTemplateOutputDefinitionRepository.class),
                mock(HookRepository.class), mock(ProcedureParameterValueRepository.class), mock(TagRepository.class),
                mock(PipeExecutionOrchestrator.class), mock(ApplicationEventLogger.class), mock(PipeBindingExpressionService.class));
        persistence = new PipeExecutionPersistenceService(pipes, mock(PipeExecutionRepository.class),
                mock(PipeStepResultRepository.class), mock(ApplicationEventLogger.class));
    }

    @AfterAll
    void closeJpa() {
        if (factory != null)
            factory.destroy();
    }

    @Test
    void keepsOneStepWithZeroOneTwoAndThreeTags() {
        assertReadsPreserved(fixture(false), 1, 0);
    }

    @Test
    void preservesStepIdentityOrderAndBindingsWithZeroOneTwoAndThreeTags() {
        assertReadsPreserved(fixture(true), 3, 3);
    }

    private void assertReadsPreserved(long id, int expectedSteps, int expectedBindings) {
        var initial = transactions.execute(status -> service.get(id));
        var expectedIds = initial.steps().stream().map(PipeStepResponse::id).toList();
        for (int tagCount = 0; tagCount <= 3; tagCount++) {
            final int count = tagCount;
            transactions.executeWithoutResult(status -> {
                Pipe pipe = pipes.findById(id).orElseThrow();
                Set<Tag> tags = new LinkedHashSet<>();
                for (int index = 0; index < count; index++) {
                    var tag = new Tag(TagScope.PIPE, "Pipe read " + UUID.randomUUID(), "#123456");
                    entityManager.persist(tag);
                    tags.add(tag);
                }
                pipe.replaceTags(tags);
                pipes.flush();
            });
            var fetched = transactions.execute(status -> service.get(id));
            assertDefinition(fetched, initial, expectedIds, count, expectedBindings);
            var searched = transactions.execute(status -> service.search(initial.name(), PageRequest.of(0, 10)).getContent().getFirst());
            assertDefinition(searched, initial, expectedIds, count, expectedBindings);
            Pipe detached = transactions.execute(status -> persistence.definition(id));
            assertDefinition(PipeMapper.response(detached), initial, expectedIds, count, expectedBindings);
            var rawIds = transactions.execute(status -> pipes.findDetailedById(id).orElseThrow().getSteps().stream().map(PipeStep::getId).toList());
            assertThat(rawIds).containsExactlyElementsOf(expectedIds);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM core.pipe_steps WHERE pipe_id = ?", Integer.class, id))
                    .isEqualTo(expectedSteps);
        }
    }

    private static void assertDefinition(PipeResponse result, PipeResponse initial, List<Long> expectedIds, int tagCount, int expectedBindings) {
        assertThat(result.tags()).hasSize(tagCount);
        assertThat(result.steps()).usingRecursiveComparison().ignoringFields("bindings").isEqualTo(initial.steps());
        for (int index = 0; index < result.steps().size(); index++)
            assertThat(result.steps().get(index).bindings()).containsExactlyInAnyOrderElementsOf(initial.steps().get(index).bindings());

        assertThat(result.steps().stream().map(PipeStepResponse::id).toList()).containsExactlyElementsOf(expectedIds);
        assertThat(result.steps().stream().mapToInt(step -> step.bindings().size()).sum()).isEqualTo(expectedBindings);
    }

    private long fixture(boolean withBindings) {
        return transactions.execute(status -> {
            var suffix = UUID.randomUUID().toString();
            var template = new ProcedureTemplateDefinition("test.PipeRead" + suffix, "test.name", "test.description",
                    "test/source", WorkerCapability.STANDARD, false, List.of());
            entityManager.persist(template);
            var resource = new Procedure(template, "Pipe read resource " + suffix, null, "-", false, false, Set.of());
            entityManager.persist(resource);
            Pipe pipe = pipes.saveAndFlush(new Pipe("Pipe read " + suffix, null, false, false));
            PipeStep source = PipeStep.procedure(pipe, "source", 0, resource, 1000, List.of());
            if (!withBindings) {
                pipe.replaceSteps(List.of(source));
            } else {
                PipeStep consumer = PipeStep.procedure(pipe, "consumer", 1, resource, 1000, List.of());
                PipeStep copy = PipeStep.procedure(pipe, "copy", 2, resource, 1000, List.of());
                var first = definition(template, "first", String.class.getName(), 0);
                var second = definition(template, "second", String.class.getName(), 1);
                var artifact = definition(template, "artifact", ProcedureArtifactInput.class.getName(), 2);
                entityManager.persist(first);
                entityManager.persist(second);
                entityManager.persist(artifact);
                var output = new ProcedureTemplateOutputDefinition(template, "backup", 0);
                entityManager.persist(output);
                consumer.addBinding(new PipeStepBinding(consumer, first, source, "/value", "{{pipe.VALUE}}"));
                consumer.addBinding(new PipeStepBinding(consumer, second, source, "/other", null));
                copy.addBinding(new PipeStepBinding(copy, artifact, source, output));
                pipe.replaceSteps(List.of(source, consumer, copy));
            }
            pipes.flush();
            return pipe.getId();
        });
    }

    private static ProcedureTemplateParameterDefinition definition(ProcedureTemplateDefinition template, String key, String type, int position) {
        return new ProcedureTemplateParameterDefinition(template, key, "test.parameter", "test.description", type,
                List.of(), true, null, false, position, false, List.of(AlertParameterSource.TEXT), List.of(), List.of());
    }
}
