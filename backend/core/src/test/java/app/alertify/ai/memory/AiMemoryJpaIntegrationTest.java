package app.alertify.ai.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import app.alertify.ai.chat.AiConversation;
import app.alertify.ai.chat.AiConversationRepository;
import app.alertify.api.error.ConflictException;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.logging.ApplicationEventLogger;
import jakarta.persistence.EntityManager;
import tools.jackson.databind.json.JsonMapper;

/** Refuses an existing core schema and runs the real migration on a disposable database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "ALERTIFY_MEMORY_TEST_DATABASE_URL", matches = "jdbc:postgresql:.+")
class AiMemoryJpaIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private LocalContainerEntityManagerFactoryBean factory;
    private DriverManagerDataSource dataSource;
    private EntityManager entityManager;
    private TransactionTemplate transactions;
    private AiMemoryRepository repository;
    private AiMemoryService memories;

    @BeforeAll
    void initializeEmptyDisposableDatabaseAndApplyTheRealMigration() throws Exception {
        dataSource = new DriverManagerDataSource(System.getenv("ALERTIFY_MEMORY_TEST_DATABASE_URL"), "postgres", "");
        var jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT to_regnamespace('core')::text", String.class)).isNull();
        Path migrations = Path.of("../../database/migrations");
        List<Path> files;
        try (var paths = Files.list(migrations)) {
            files = paths.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted(java.util.Comparator.comparingInt(path -> Integer.parseInt(path.getFileName().toString().split("\\.")[0]))).toList();
        }
        for (Path migration : files)
            jdbc.execute(Files.readString(migration));

        initializeJpa("validate");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.log_events WHERE code IN ('AI_MEMORY_SAVED','AI_MEMORY_DELETED')", Integer.class)).isEqualTo(2);
    }

    private void initializeJpa(String schemaAction) {
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setPackagesToScan("app.alertify.ai.chat", "app.alertify.ai.memory");
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", schemaAction, "hibernate.jdbc.time_zone", "UTC"));
        factory.afterPropertiesSet();
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        transactions = new TransactionTemplate(new JpaTransactionManager(factory.getObject()));
        var repositories = new JpaRepositoryFactory(entityManager);
        repository = repositories.getRepository(AiMemoryRepository.class);
        memories = new AiMemoryService(repository, repositories.getRepository(AiConversationRepository.class), mock(ApplicationEventLogger.class), JSON);
    }

    @AfterAll
    void closeJpa() {
        if (factory != null)
            factory.destroy();

    }

    @Test
    void scopesKeysToTheirOwnerAndRejectsDuplicateCreationsAndStaleUpdatesOrDeletes() {
        Long conversation = conversation("alice");
        var created = transactions.execute(status -> memories.save("alice", conversation, "maven.protobuf", "  original preference  ", null));
        assertThat(created.version()).isZero();
        assertThat(created.content()).isEqualTo("original preference");
        assertThat(created.sourceConversationId()).isEqualTo(conversation);
        assertThat(created.createdAt()).isNotNull();
        assertThat(transactions.execute(status -> memories.search("bob", "", null, null)).memories()).isEmpty();
        assertThatThrownBy(() -> transactions.execute(status -> memories.save("bob", conversation, "bad.origin", "content", null)))
                .isInstanceOf(ResourceNotFoundException.class);
        transactions.execute(status -> memories.save("bob", null, "maven.protobuf", "bob's separate preference", null));
        assertThatThrownBy(() -> transactions.execute(status -> memories.save("alice", null, "maven.protobuf", "overwrite", null)))
                .isInstanceOf(ConflictException.class);
        var updated = transactions.execute(status -> memories.save("alice", null, "maven.protobuf", "updated preference", created.version()));
        assertThat(updated.version()).isEqualTo(1);
        assertThatThrownBy(() -> transactions.execute(status -> memories.save("alice", null, "maven.protobuf", "stale", created.version())))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> transactions.execute(status -> memories.delete("alice", "maven.protobuf", created.version())))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> transactions.execute(status -> memories.delete("charlie", "maven.protobuf", updated.version())))
                .isInstanceOf(ResourceNotFoundException.class);
        transactions.execute(status -> memories.delete("alice", "maven.protobuf", updated.version()));
        assertThat(transactions.execute(status -> memories.search("alice", "maven.protobuf", null, null)).memories()).isEmpty();
        assertThat(transactions.execute(status -> memories.search("bob", "maven.protobuf", null, null)).memories())
                .singleElement().satisfies(entry -> assertThat(entry.content()).isEqualTo("bob's separate preference"));
    }

    @Test
    void deletingTheOriginChatKeepsTheMemoryAndClearsOnlyItsSourceReference() {
        Long source = conversation("retained-owner");
        transactions.execute(status -> memories.save("retained-owner", source, "retained.lesson", "verified reusable correction", null));
        new JdbcTemplate(dataSource).update("DELETE FROM core.ai_conversations WHERE id = ?", source);
        var retained = transactions.execute(status -> memories.search("retained-owner", "", null, null));
        assertThat(retained.memories()).singleElement().satisfies(entry -> {
            assertThat(entry.content()).isEqualTo("verified reusable correction");
            assertThat(entry.sourceConversationId()).isNull();
            assertThat(entry.version()).isZero();
        });
    }

    @Test
    void retrievesAllOversizedSearchPagesWithoutDuplicatesAndSurvivesAnApplicationPersistenceRestart() {
        transactions.execute(status -> {
            for (int index = 0; index < 21; index++)
                memories.save("paging-owner", null, "page." + String.format("%03d", index), "\u0001".repeat(4000), null);

            return null;
        });
        factory.destroy();
        initializeJpa("validate");
        List<String> keys = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 21; page++) {
            String currentCursor = cursor;
            var result = transactions.execute(status -> memories.search("paging-owner", "", 20, currentCursor));
            assertThat(JSON.writeValueAsBytes(result).length).isLessThan(128 * 1024);
            keys.addAll(result.memories().stream().map(AiMemoryService.Memory::key).toList());
            cursor = result.nextCursor();
            if (cursor == null)
                break;

        }
        assertThat(cursor).isNull();
        assertThat(keys).hasSize(21).doesNotHaveDuplicates();
        assertThat(transactions.execute(status -> memories.search("paging-owner", "%", null, null)).memories()).isEmpty();
        var index = transactions.execute(status -> memories.index("paging-owner"));
        assertThat(index.entries()).hasSize(21);
        assertThat(index.incomplete()).isFalse();
    }

    @Test
    @Timeout(15)
    void actualConcurrentEditsCannotOverwriteEachOther() throws Exception {
        transactions.execute(status -> memories.save("concurrent-owner", null, "concurrent.lesson", "original", null));
        var barrier = new CyclicBarrier(2);
        Callable<Boolean> update = () -> {
            try {
                transactions.execute(status -> {
                    var current = repository.findByUserSubjectAndMemoryKey("concurrent-owner", "concurrent.lesson").orElseThrow();
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception exception) {
                        throw new IllegalStateException("Concurrent test could not synchronize", exception);
                    }
                    memories.save("concurrent-owner", null, "concurrent.lesson", "updated by " + Thread.currentThread().threadId(), current.getVersion());
                    return null;
                });
                return true;
            } catch (org.springframework.dao.OptimisticLockingFailureException | jakarta.persistence.OptimisticLockException exception) {
                return false;
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(update);
            var second = executor.submit(update);
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(transactions.execute(status -> memories.search("concurrent-owner", "", null, null)).memories())
                .singleElement().satisfies(entry -> assertThat(entry.version()).isEqualTo(1));
    }

    @Test
    void administrativeManagementPagesAllOwnersAndRejectsStaleDeletionWithoutNeedingAWorker() {
        var management = new AiMemoryManagementService(repository, mock(ApplicationEventLogger.class));
        transactions.execute(status -> {
            memories.save("managed-alice", null, "managed.lesson", "alice content", null);
            memories.save("managed-bob", null, "managed.lesson", "bob content", null);
            return null;
        });
        var first = transactions.execute(status -> management.list(0, 1));
        var second = transactions.execute(status -> management.list(1, 1));
        assertThat(first.totalElements()).isGreaterThanOrEqualTo(2);
        assertThat(first.content()).hasSize(1);
        assertThat(first.content().getFirst().id()).isNotEqualTo(second.content().getFirst().id());
        var summaries = transactions.execute(status -> management.list(0, 50)).content();
        assertThat(summaries).extracting(AiMemoryManagementService.Summary::userSubject).contains("managed-alice", "managed-bob");
        var alice = summaries.stream().filter(value -> value.userSubject().equals("managed-alice")).findFirst().orElseThrow();
        assertThat(transactions.execute(status -> management.get(alice.id())).content()).isEqualTo("alice content");
        transactions.execute(status -> memories.save("managed-alice", null, alice.key(), "changed content", alice.version()));
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> management.delete(alice.id(), alice.version())))
                .isInstanceOf(ConflictException.class);
        var latest = transactions.execute(status -> management.get(alice.id()));
        transactions.executeWithoutResult(status -> management.delete(alice.id(), latest.version()));
        assertThatThrownBy(() -> transactions.execute(status -> management.get(alice.id()))).isInstanceOf(ResourceNotFoundException.class);
        // Roll back the global deletion so independent test cases keep their own fixtures.
        transactions.executeWithoutResult(status -> {
            assertThat(management.deleteAll()).isPositive();
            assertThat(management.list(0, 20).totalElements()).isZero();
            status.setRollbackOnly();
        });
        assertThat(transactions.execute(status -> memories.search("managed-bob", "managed.lesson", null, null)).memories()).hasSize(1);
    }

    private Long conversation(String owner) {
        return transactions.execute(status -> {
            var conversation = new AiConversation(owner, owner, "memory test", "DIRECT", "ASK");
            entityManager.persist(conversation);
            entityManager.flush();
            return conversation.getId();
        });
    }
}
