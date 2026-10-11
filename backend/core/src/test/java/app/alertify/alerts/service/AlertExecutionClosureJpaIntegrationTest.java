package app.alertify.alerts.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import app.alertify.ai.AiProvenance;
import app.alertify.alerts.model.AlertExecutionClosureAudit;
import app.alertify.jpa.repository.AlertExecutionClosureAuditRepository;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Runs against an empty disposable PostgreSQL database; existing schemas are rejected.
 * Provide ALERTIFY_AUDIT_TEST_DATABASE_URL and ALERTIFY_AUDIT_TEST_MIGRATIONS (the SQL directory).
 * Optional DATABASE_USER/PASSWORD variables use the same ALERTIFY_AUDIT_TEST_ prefix.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "ALERTIFY_AUDIT_TEST_DATABASE_URL", matches = "jdbc:postgresql:.+"),
        @EnabledIfEnvironmentVariable(named = "ALERTIFY_AUDIT_TEST_MIGRATIONS", matches = ".+")
})
class AlertExecutionClosureJpaIntegrationTest {

    private static final UUID HISTORICAL_EXECUTION = UUID.fromString("9e2cb54b-8fea-44fb-97af-bff5d73ef7c5");
    private static final Instant AT = Instant.parse("2026-10-03T12:00:00Z");
    private JdbcTemplate jdbc;
    private LocalContainerEntityManagerFactoryBean factory;
    private EntityManager entityManager;
    private AlertExecutionClosureAuditRepository audits;
    private TransactionTemplate transactions;

    @BeforeAll
    void migrateAndInitializeJpa() throws Exception {
        var environment = System.getenv();
        var dataSource = new DriverManagerDataSource(environment.get("ALERTIFY_AUDIT_TEST_DATABASE_URL"),
                environment.getOrDefault("ALERTIFY_AUDIT_TEST_DATABASE_USER", "postgres"),
                environment.getOrDefault("ALERTIFY_AUDIT_TEST_DATABASE_PASSWORD", ""));
        jdbc = new JdbcTemplate(dataSource);
        Path migrations = Path.of(environment.get("ALERTIFY_AUDIT_TEST_MIGRATIONS"));
        // Reject an existing schema before running the complete migration chain.
        assertNull(jdbc.queryForObject("SELECT to_regnamespace('core')::text", String.class));
        assertNull(jdbc.queryForObject("SELECT to_regnamespace('audit')::text", String.class));
        assertNull(jdbc.queryForObject("SELECT to_regnamespace('secrets')::text", String.class));
        jdbc.execute(Files.readString(migrations.resolve("1.initial.sql")));
        jdbc.execute(Files.readString(migrations.resolve("2.smart-execution-once-per-interval.sql")));
        jdbc.execute("""
                INSERT INTO core.alert_templates (id, template_key, name_key, description_key, required_capability)
                    VALUES (-1, 'test.ClosureAudit', 'test.name', 'test.description', 'STANDARD');
                INSERT INTO core.alerts (id, alert_template_id, name, cron_expression, enabled)
                    VALUES (3, -1, 'Closure audit fixture', '-', false);
                """);
        jdbc.update("""
                INSERT INTO core.alert_executions (id, execution_id, alert_id, status, started_at, work_started_at, finished_at)
                    VALUES (-1, ?, 3, 'WARN', current_timestamp, current_timestamp, current_timestamp)
                """, HISTORICAL_EXECUTION);
        jdbc.execute(Files.readString(migrations.resolve("3.alert-execution-origin.sql")));
        try (var files = Files.list(migrations)) {
            for (Path file : files.filter(path -> path.getFileName().toString().matches("[4-9][0-9]*\\..*\\.sql"))
                    .sorted(java.util.Comparator.comparingInt(path -> Integer.parseInt(path.getFileName().toString().split("\\.")[0]))).toList())
                jdbc.execute(Files.readString(file));
        }
        jdbc.update("""
                INSERT INTO audit.alert_execution_closure_audit
                    (id, execution_id, alert_id, closed, actor_subject, actor_name, note, changed_at)
                VALUES (-1, ?, 3, true, 'historical-subject', 'historical-admin', 'retained', ?)
                """, HISTORICAL_EXECUTION, java.time.OffsetDateTime.ofInstant(AT, java.time.ZoneOffset.UTC));
        jdbc.execute(Files.readString(migrations.resolve("6.ai-tool-audit.sql")));

        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setManagedTypes(PersistenceManagedTypes.of(AlertExecutionClosureAudit.class.getName(), ClosureState.class.getName()));
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.jdbc.time_zone", "UTC"));
        factory.afterPropertiesSet();
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        audits = new JpaRepositoryFactory(entityManager).getRepository(AlertExecutionClosureAuditRepository.class);
        transactions = new TransactionTemplate(new JpaTransactionManager(factory.getObject()));
    }

    @AfterAll
    void closeJpa() {
        if (factory != null)
            factory.destroy();
    }

    @Test
    void consolidatedMigrationCreatesAuditIndexesIdentityAndImmutableTrigger() {
        assertNotNull(jdbc.queryForObject("SELECT to_regclass('audit.alert_execution_closure_audit')::text", String.class));
        assertNotNull(jdbc.queryForObject("SELECT to_regprocedure('audit.reject_closure_audit_mutation()')::text", String.class));
        assertNull(jdbc.queryForObject("SELECT to_regclass('core.alert_execution_closure_audit')::text", String.class));
        assertNotNull(jdbc.queryForObject("SELECT to_regclass('audit.idx_alert_closure_audit_execution')::text", String.class));
        assertNotNull(jdbc.queryForObject("SELECT to_regclass('audit.idx_revinfo_ai_conversation')::text", String.class));
        assertNotNull(jdbc.queryForObject("SELECT to_regclass('audit.idx_alert_closure_ai_conversation')::text", String.class));
        assertNotNull(jdbc.queryForObject("SELECT to_regclass('core.idx_alert_executions_ai_conversation')::text", String.class));
        assertTrue(jdbc.queryForObject("SELECT pg_get_serial_sequence('audit.alert_execution_closure_audit', 'id')", String.class).startsWith("audit."));
        assertFalse(jdbc.queryForObject("SELECT ai_assisted FROM core.alert_executions WHERE id = -1", Boolean.class));
        assertNull(jdbc.queryForObject("SELECT ai_conversation_id FROM core.alert_executions WHERE id = -1", Long.class));
        var history = audits.findByExecutionIdOrderByIdAsc(HISTORICAL_EXECUTION);
        assertEquals(1, history.size());
        var entry = history.getFirst();
        assertEquals(-1L, entry.getId());
        assertEquals(3L, entry.getAlertId());
        assertTrue(entry.isClosed());
        assertEquals("historical-subject", entry.getActorSubject());
        assertEquals("historical-admin", entry.getActorName());
        assertEquals("retained", entry.getNote());
        assertEquals(AT, entry.getChangedAt());
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE audit.alert_execution_closure_audit SET note = 'changed' WHERE id = -1"));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM audit.alert_execution_closure_audit WHERE id = -1"));
    }

    @Test
    void pipeAlertBindingsAndExpressionsHaveFinalConstraintsAndAuditColumns() {
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'audit' AND table_name = 'pipe_step_bindings_aud' AND column_name IN ('target_alert_parameter_id', 'value_expression')", Integer.class));
        assertEquals("YES", jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'core' AND table_name = 'pipe_step_bindings' AND column_name = 'target_parameter_id'", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'core' AND table_name = 'pipe_step_bindings' AND column_name = 'target_procedure_parameter_id'", Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT to_regclass('core.uq_pipe_step_bindings_target_alert_parameter')::text", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid = 'core.pipe_step_bindings'::regclass AND conname = 'ck_pipe_step_bindings_alert_source'", Integer.class));
        var failure = org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.execute("INSERT INTO core.pipe_step_bindings(target_step_id, source_step_id, source_result_pointer) VALUES (-99, -98, '/accessToken')"));
        assertTrue(failure.getMessage().contains("ck_pipe_step_bindings_target"));
    }

    @Test
    void persistsAndReadsOrderedEventsWithoutRequiringTheirOriginalExecution() {
        UUID executionId = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            var closure = audits.save(new AlertExecutionClosureAudit(executionId, 99L, true, "subject", "admin", "resolved", AT, AiProvenance.NONE));
            var reopening = audits.save(new AlertExecutionClosureAudit(executionId, 99L, false, "subject", "reviewer", null, AT.plusSeconds(1), AiProvenance.NONE));
            entityManager.flush();
            entityManager.clear();
            var history = audits.findByExecutionIdOrderByIdAsc(executionId);
            assertEquals(2, history.size());
            assertTrue(closure.getId() > 0 && reopening.getId() > closure.getId());
            assertEquals(closure.getId(), history.getFirst().getId());
            assertTrue(history.getFirst().isClosed());
            assertFalse(history.getLast().isClosed());
            assertEquals("reviewer", history.getLast().getActorName());
            assertNull(history.getLast().getNote());
            assertEquals(AT.plusSeconds(1), history.getLast().getChangedAt());
            status.setRollbackOnly();
        });
        assertTrue(audits.findByExecutionIdOrderByIdAsc(executionId).isEmpty());
    }

    @Test
    void auditFailureRollsBackTheChangedClosure() {
        UUID executionId = UUID.randomUUID();
        assertThrows(RuntimeException.class, () -> transactions.executeWithoutResult(status -> {
            entityManager.find(ClosureState.class, -1L).close();
            entityManager.flush();
            audits.save(new AlertExecutionClosureAudit(executionId, 3L, true, "subject", "admin", "x".repeat(2001), AT, AiProvenance.NONE));
            entityManager.flush();
        }));
        assertFalse(jdbc.queryForObject("SELECT closed FROM core.alert_executions WHERE id = -1", Boolean.class));
        assertTrue(audits.findByExecutionIdOrderByIdAsc(executionId).isEmpty());
    }

    @Test
    void laterFailureRollsBackBothClosureAndInsertedAudit() {
        UUID executionId = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            entityManager.find(ClosureState.class, -1L).close();
            audits.save(new AlertExecutionClosureAudit(executionId, 3L, true, "subject", "admin", null, AT, AiProvenance.NONE));
            entityManager.flush();
            throw new IllegalStateException("Simulated failure after the audit was written");
        }));
        assertFalse(jdbc.queryForObject("SELECT closed FROM core.alert_executions WHERE id = -1", Boolean.class));
        assertTrue(audits.findByExecutionIdOrderByIdAsc(executionId).isEmpty());
    }

    @Entity(name = "ClosureAuditTestState")
    @Table(name = "alert_executions", schema = "core")
    static class ClosureState {
        @Id
        private Long id;

        @Column(nullable = false)
        private boolean closed;

        @Column(name = "closure_at")
        private Instant closureAt;

        @Column(name = "closure_by", columnDefinition = "text")
        private String closureBy;

        protected ClosureState() {
        }

        void close() {
            closed = true;
            closureAt = AT;
            closureBy = "admin";
        }
    }
}
