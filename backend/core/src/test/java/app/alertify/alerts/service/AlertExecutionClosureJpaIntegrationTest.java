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
    private long originalTableOid;
    private long originalFunctionOid;

    @BeforeAll
    void migrateAndInitializeJpa() throws Exception {
        var environment = System.getenv();
        var dataSource = new DriverManagerDataSource(environment.get("ALERTIFY_AUDIT_TEST_DATABASE_URL"),
                environment.getOrDefault("ALERTIFY_AUDIT_TEST_DATABASE_USER", "postgres"),
                environment.getOrDefault("ALERTIFY_AUDIT_TEST_DATABASE_PASSWORD", ""));
        jdbc = new JdbcTemplate(dataSource);
        // Only the columns required by migration 4 and the closure state fixture are needed here.
        jdbc.execute("""
                CREATE SCHEMA core;
                CREATE SCHEMA audit;
                CREATE TABLE core.alert_executions (
                    id bigint PRIMARY KEY, alert_id bigint NOT NULL, status varchar(16) NOT NULL,
                    finished_at timestamptz NOT NULL
                );
                INSERT INTO core.alert_executions VALUES (-1, 3, 'WARN', current_timestamp);
                """);
        Path migrations = Path.of(environment.get("ALERTIFY_AUDIT_TEST_MIGRATIONS"));
        jdbc.execute(Files.readString(migrations.resolve("4.alert-execution-closure.sql")));
        jdbc.update("""
                INSERT INTO core.alert_execution_closure_audit
                    (id, execution_id, alert_id, closed, actor_subject, actor_name, note, changed_at)
                VALUES (-1, ?, 3, true, 'historical-subject', 'historical-admin', 'retained', ?)
                """, HISTORICAL_EXECUTION, java.time.OffsetDateTime.ofInstant(AT, java.time.ZoneOffset.UTC));
        originalTableOid = jdbc.queryForObject("SELECT 'core.alert_execution_closure_audit'::regclass::oid::bigint", Long.class);
        originalFunctionOid = jdbc.queryForObject("SELECT 'core.reject_closure_audit_mutation()'::regprocedure::oid::bigint", Long.class);
        jdbc.execute(Files.readString(migrations.resolve("8.alert-execution-closure-audit-schema.sql")));

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
    void migrationPreservesHistoricalRowsIndexesIdentityAndTrigger() {
        assertEquals(originalTableOid, jdbc.queryForObject("SELECT 'audit.alert_execution_closure_audit'::regclass::oid::bigint", Long.class));
        assertEquals(originalFunctionOid, jdbc.queryForObject("SELECT 'audit.reject_closure_audit_mutation()'::regprocedure::oid::bigint", Long.class));
        assertNull(jdbc.queryForObject("SELECT to_regclass('core.alert_execution_closure_audit')::text", String.class));
        assertNotNull(jdbc.queryForObject("SELECT to_regclass('audit.idx_alert_closure_audit_execution')::text", String.class));
        assertTrue(jdbc.queryForObject("SELECT pg_get_serial_sequence('audit.alert_execution_closure_audit', 'id')", String.class).startsWith("audit."));
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
    void persistsAndReadsOrderedEventsWithoutRequiringTheirOriginalExecution() {
        UUID executionId = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            var closure = audits.save(new AlertExecutionClosureAudit(executionId, 99L, true, "subject", "admin", "resolved", AT));
            var reopening = audits.save(new AlertExecutionClosureAudit(executionId, 99L, false, "subject", "reviewer", null, AT.plusSeconds(1)));
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
            audits.save(new AlertExecutionClosureAudit(executionId, 3L, true, "subject", "admin", "x".repeat(2001), AT));
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
            audits.save(new AlertExecutionClosureAudit(executionId, 3L, true, "subject", "admin", null, AT));
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
