package app.alertify.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

import app.alertify.jpa.entity.ApplicationLog;
import app.alertify.jpa.entity.ApplicationLogEvent;
import app.alertify.jpa.entity.ApplicationLogLevelDefinition;
import app.alertify.jpa.entity.ApplicationLogSource;
import app.alertify.jpa.repository.ApplicationLogEventRepository;
import app.alertify.jpa.repository.ApplicationLogLevelDefinitionRepository;
import app.alertify.jpa.repository.ApplicationLogRepository;
import app.alertify.jpa.repository.ApplicationLogSourceRepository;
import tools.jackson.databind.json.JsonMapper;

/** Validates log persistence against an empty disposable database only. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(named = "ALERTIFY_LOG_TEST_DATABASE_URL", matches = "jdbc:postgresql:.+"),
        @EnabledIfEnvironmentVariable(named = "ALERTIFY_LOG_TEST_MIGRATIONS", matches = ".+")
})
class ApplicationEventLoggerJpaIntegrationTest {

    private JdbcTemplate jdbc;
    private LocalContainerEntityManagerFactoryBean factory;
    private TransactionTemplate transactions;
    private ApplicationEventLogger logger;

    @BeforeAll
    void initializeEmptyDatabaseAndJpa() throws Exception {
        Map<String, String> environment = System.getenv();
        var dataSource = new DriverManagerDataSource(environment.get("ALERTIFY_LOG_TEST_DATABASE_URL"),
                environment.getOrDefault("ALERTIFY_LOG_TEST_DATABASE_USER", "postgres"),
                environment.getOrDefault("ALERTIFY_LOG_TEST_DATABASE_PASSWORD", ""));
        jdbc = new JdbcTemplate(dataSource);
        for (String schema : List.of("core", "audit", "secrets"))
            assertThat(jdbc.queryForObject("SELECT to_regnamespace(?)::text", String.class, schema)).isNull();

        Path directory = Path.of(environment.get("ALERTIFY_LOG_TEST_MIGRATIONS"));
        try (var files = Files.list(directory)) {
            for (Path migration : files.filter(path -> path.getFileName().toString().matches("[0-9]+\\..*\\.sql"))
                    .sorted(Comparator.comparingInt(path -> Integer.parseInt(path.getFileName().toString().split("\\.")[0]))).toList())
                jdbc.execute(Files.readString(migration));

        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.log_events WHERE code = 'WEBSOCKET_AUTHENTICATION_FAILED'", Integer.class)).isEqualTo(1);

        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setManagedTypes(PersistenceManagedTypes.of(ApplicationLog.class.getName(), ApplicationLogEvent.class.getName(), ApplicationLogLevelDefinition.class.getName(), ApplicationLogSource.class.getName()));
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.jdbc.time_zone", "UTC"));
        factory.afterPropertiesSet();
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        var repositories = new JpaRepositoryFactory(entityManager);
        var catalog = new ApplicationLogCatalog(repositories.getRepository(ApplicationLogLevelDefinitionRepository.class),
                repositories.getRepository(ApplicationLogSourceRepository.class), repositories.getRepository(ApplicationLogEventRepository.class));
        var writer = new ApplicationLogWriter(repositories.getRepository(ApplicationLogRepository.class), catalog, JsonMapper.builder().build());
        logger = new ApplicationEventLogger(writer, "alertify-backend");
        transactions = new TransactionTemplate(new JpaTransactionManager(factory.getObject()));
    }

    @AfterAll
    void closeJpa() {
        if (factory != null)
            factory.destroy();

    }

    @Test
    void persistsVerifiedIdentityAndTheOriginalRequestAfterAuth() {
        Jwt jwt = Jwt.withTokenValue("test-only-token").header("alg", "RS256").subject("verified-subject").claim("preferred_username", "alice").build();
        var authentication = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        RequestLogContext request = new RequestLogContext(UUID.randomUUID(), "/alertify/api/admin/events", "GET", System.nanoTime());
        var data = request.data(101);
        data.put("sessionId", "session-success");

        transactions.executeWithoutResult(status -> logger.success("API_REQUEST", data, authentication, request));

        var row = jdbc.queryForMap("SELECT l.username, l.user_subject, l.path, l.outcome, l.data::text AS data, e.code FROM audit.logs l JOIN audit.log_events e ON e.id = l.event_id WHERE l.request_id = ?", request.requestId());
        assertThat(row).containsEntry("username", "alice").containsEntry("user_subject", "verified-subject").containsEntry("path", request.path()).containsEntry("outcome", "SUCCESS").containsEntry("code", "API_REQUEST");
        assertThat(row.get("data").toString()).contains("session-success").doesNotContain("test-only-token");
    }

    @Test
    void persistsTheNewFailureEventWithAnonymousIdentity() {
        RequestLogContext request = new RequestLogContext(UUID.randomUUID(), "/api/viewer/events", "GET", System.nanoTime());
        var data = request.data(101);
        data.put("reason", "AUTH_TIMEOUT");
        data.put("phase", "INITIAL");

        transactions.executeWithoutResult(status -> logger.failure("WEBSOCKET_AUTHENTICATION_FAILED", data, null, request));

        var row = jdbc.queryForMap("SELECT l.username, l.user_subject, l.outcome, l.path, e.code FROM audit.logs l JOIN audit.log_events e ON e.id = l.event_id WHERE l.request_id = ?", request.requestId());
        assertThat(row).containsEntry("username", "anonymous").containsEntry("user_subject", "anonymous").containsEntry("outcome", "FAILURE").containsEntry("path", request.path()).containsEntry("code", "WEBSOCKET_AUTHENTICATION_FAILED");
    }
}
