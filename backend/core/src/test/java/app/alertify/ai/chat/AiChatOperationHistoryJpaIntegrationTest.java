package app.alertify.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;

/** Uses only an empty disposable PostgreSQL database; refuses an existing core schema. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "ALERTIFY_CHAT_HISTORY_TEST_DATABASE_URL", matches = "jdbc:postgresql:.+")
class AiChatOperationHistoryJpaIntegrationTest {
    private LocalContainerEntityManagerFactoryBean factory;
    private EntityManager entityManager;
    private TransactionTemplate transactions;
    private AiToolInvocationRepository invocations;
    private AiMessageRepository messages;

    @BeforeAll
    void initializeEmptyDisposableDatabase() {
        var dataSource = new DriverManagerDataSource(System.getenv("ALERTIFY_CHAT_HISTORY_TEST_DATABASE_URL"), "postgres", "");
        var jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT to_regnamespace('core')::text", String.class)).isNull();
        jdbc.execute("CREATE SCHEMA core");
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setPackagesToScan("app.alertify.ai.chat");
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop", "hibernate.jdbc.time_zone", "UTC"));
        factory.afterPropertiesSet();
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        transactions = new TransactionTemplate(new JpaTransactionManager(factory.getObject()));
        var repositories = new JpaRepositoryFactory(entityManager);
        invocations = repositories.getRepository(AiToolInvocationRepository.class);
        messages = repositories.getRepository(AiMessageRepository.class);
    }

    @AfterAll
    void closeJpa() {
        if (factory != null)
            factory.destroy();
    }

    @Test
    void boundsHistoryByConversationTurnCursorAndPageWithoutDroppingFailedOrRejectedOperations() {
        long[] ids = transactions.execute(status -> {
            var own = conversation("own");
            var other = conversation("other");
            var first = turn(own);
            var succeeded = invocation(first, "DOMAIN");
            succeeded.succeed("{}");
            var failed = invocation(first, "DOMAIN");
            failed.fail("safe error", "TEST_ERROR");
            var rejected = invocation(first, "DOMAIN");
            rejected.reject("user rejected", "USER_REJECTED");
            invocation(first, "INTERNAL").succeed("internal");
            var later = turn(own);
            invocation(later, "DOMAIN").succeed("future");
            invocation(turn(other), "DOMAIN").succeed("other conversation");
            entityManager.flush();
            return new long[] { own.getId(), first.getId(), succeeded.getId(), failed.getId(), rejected.getId() };
        });

        var recent = transactions.execute(status -> invocations.findOperationHistory(ids[0], ids[1], 0, PageRequest.of(0, 2)));

        assertThat(recent).extracting(AiToolInvocation::getId).containsExactly(ids[4], ids[3]);
        assertThat(recent).extracting(AiToolInvocation::getStatus).containsExactly("REJECTED", "FAILED");
        // Turn metadata remains available after the transaction, with open-in-view absent.
        assertThat(recent.getFirst().getTurn().getCreatedAt()).isNotNull();
        var older = transactions.execute(status -> invocations.findOperationHistory(ids[0], ids[1], ids[3], PageRequest.of(0, 2)));
        assertThat(older).extracting(AiToolInvocation::getId).containsExactly(ids[2]);
    }

    @Test
    void messageHistoryFetchesTurnDatesAndRetainsMessagesWithoutATurn() {
        Long conversationId = transactions.execute(status -> {
            var conversation = conversation("message dates");
            var turn = turn(conversation);
            entityManager.persist(new AiMessage(conversation, turn, "USER", "request"));
            entityManager.persist(new AiMessage(conversation, null, "SYSTEM", "legacy message"));
            entityManager.flush();
            return conversation.getId();
        });

        List<AiMessage> history = transactions.execute(status -> messages.findAllByConversation_IdOrderById(conversationId));

        assertThat(history).hasSize(2);
        assertThat(history.getFirst().getTurnCreatedAt()).isNotNull();
        assertThat(history.getFirst().getCreatedAt()).isNotNull();
        assertThat(history.getLast().getTurnCreatedAt()).isNull();
        assertThat(AiChatOperationHistory.messageContent(history.getLast())).contains("turnId=null", "legacy message");
    }

    private AiConversation conversation(String subject) {
        var conversation = new AiConversation(subject, subject, "test", "DIRECT", "ASK");
        entityManager.persist(conversation);
        return conversation;
    }

    private AiTurn turn(AiConversation conversation) {
        var turn = new AiTurn(conversation, "test-model", "low");
        entityManager.persist(turn);
        return turn;
    }

    private AiToolInvocation invocation(AiTurn turn, String kind) {
        var invocation = new AiToolInvocation(turn, 1, "call", "alertify_alert_update", "{\"id\":25}",
                kind, true, true, false, false, true, false, "ALERTS", false);
        entityManager.persist(invocation);
        return invocation;
    }
}
