package app.alertify.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import tools.jackson.databind.json.JsonMapper;
import app.alertify.api.error.ResourceNotFoundException;

/** Uses only an empty disposable PostgreSQL database; refuses an existing core schema. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "ALERTIFY_CHAT_HISTORY_TEST_DATABASE_URL", matches = "jdbc:postgresql:.+")
class AiChatOperationHistoryJpaIntegrationTest {
    private LocalContainerEntityManagerFactoryBean factory;
    private EntityManager entityManager;
    private TransactionTemplate transactions;
    private AiToolInvocationRepository invocations;
    private AiMessageRepository messages;
    private AiConversationHistory history;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

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
        history = new AiConversationHistory(repositories.getRepository(AiConversationRepository.class), messages, json);
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

    @Test
    void searchesAndPagesOnlyOtherChatsOwnedByTheTrustedSubject() {
        long[] ids = transactions.execute(status -> {
            var current = conversation("search-owner");
            var own = conversation("search-owner");
            var other = conversation("search-other");
            var titleOnly = new AiConversation("search-owner", "owner", "UniqueTitleWord", "DIRECT", "ASK");
            entityManager.persist(titleOnly);
            message(current, "USER", "certificate current");
            var first = message(own, "USER", "Certificate older");
            var second = message(own, "ASSISTANT", "certificate newer");
            message(own, "SYSTEM", "certificate INTERNAL");
            message(own, "TOOL", "certificate PRIVATE-TOOL");
            var foreign = message(other, "USER", "certificate FOREIGN");
            message(titleOnly, "USER", "unrelated body");
            message(titleOnly, "ASSISTANT", "latest unrelated body");
            entityManager.flush();
            return new long[] { current.getId(), own.getId(), first.getId(), second.getId(), other.getId(), foreign.getId(), titleOnly.getId() };
        });
        var first = transactions.execute(status -> history.search("search-owner", ids[0], "{\"query\":\"CERTIFICATE\",\"limit\":1}"));
        assertThat(first.matches()).singleElement().satisfies(entry -> {
            assertThat(entry.messageId()).isEqualTo(ids[3]);
            assertThat(entry.conversationId()).isEqualTo(ids[1]);
        });
        assertThat(first.nextCursor()).isNotBlank();
        var older = transactions.execute(status -> history.search("search-owner", ids[0], json.writeValueAsString(Map.of(
                "query", "CERTIFICATE", "limit", 1, "cursor", first.nextCursor()))));
        assertThat(older.matches()).extracting(AiConversationHistory.SearchEntry::messageId).containsExactly(ids[2]);
        assertThat(older.nextCursor()).isNull();
        var title = transactions.execute(status -> history.search("search-owner", ids[0], "{\"query\":\"UniqueTitleWord\"}"));
        assertThat(title.matches()).extracting(AiConversationHistory.SearchEntry::conversationId).containsExactly(ids[6]);
        assertThat(transactions.execute(status -> history.search("search-owner", ids[0], "{\"query\":\"%\"}")).matches()).isEmpty();
        assertThat(transactions.execute(status -> history.search("search-owner", ids[0], "{\"query\":\"certificate\",\"until\":\"2000-01-01T00:00:00Z\"}")).matches()).isEmpty();
        assertThatThrownBy(() -> transactions.execute(status -> history.search("search-other", ids[4], json.writeValueAsString(Map.of(
                "query", "CERTIFICATE", "cursor", first.nextCursor()))))).isInstanceOf(IllegalArgumentException.class);
        for (long inaccessible : new long[] { ids[0], ids[4], Long.MAX_VALUE })
            assertThatThrownBy(() -> transactions.execute(status -> history.messages("search-owner", ids[0],
                    "{\"conversationId\":" + inaccessible + "}"))).isInstanceOf(ResourceNotFoundException.class);

        assertThatThrownBy(() -> transactions.execute(status -> history.messages("search-owner", ids[0],
                "{\"conversationId\":" + ids[1] + ",\"aroundMessageId\":" + ids[5] + "}")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void readsChronologicalWindowsAndAdjacentPagesWithBoundedValidJson() {
        long[] ids = transactions.execute(status -> {
            var current = conversation("window-owner");
            var own = conversation("window-owner");
            var first = message(own, "USER", "first");
            var middle = message(own, "ASSISTANT", "middle");
            var last = message(own, "USER", "last");
            message(own, "SYSTEM", "private system");
            entityManager.flush();
            return new long[] { current.getId(), own.getId(), first.getId(), middle.getId(), last.getId() };
        });
        var latest = transactions.execute(status -> history.messages("window-owner", ids[0], "{\"conversationId\":" + ids[1] + ",\"limit\":2}"));
        assertThat(latest.messages()).extracting(AiConversationHistory.MessageEntry::messageId).containsExactly(ids[3], ids[4]);
        assertThat(latest.nextCursor()).isNull();
        var previous = transactions.execute(status -> history.messages("window-owner", ids[0], json.writeValueAsString(Map.of(
                "conversationId", ids[1], "limit", 2, "cursor", latest.previousCursor()))));
        assertThat(previous.messages()).extracting(AiConversationHistory.MessageEntry::messageId).containsExactly(ids[2]);
        var forward = transactions.execute(status -> history.messages("window-owner", ids[0], json.writeValueAsString(Map.of(
                "conversationId", ids[1], "limit", 2, "cursor", previous.nextCursor()))));
        assertThat(forward.messages()).extracting(AiConversationHistory.MessageEntry::messageId).containsExactly(ids[3], ids[4]);
        var around = transactions.execute(status -> history.messages("window-owner", ids[0], "{\"conversationId\":" + ids[1] + ",\"aroundMessageId\":" + ids[3] + ",\"limit\":3}"));
        assertThat(around.messages()).extracting(AiConversationHistory.MessageEntry::messageId).containsExactly(ids[2], ids[3], ids[4]);
        assertThatThrownBy(() -> transactions.execute(status -> history.messages("window-owner", ids[0], json.writeValueAsString(Map.of(
                "conversationId", ids[1], "aroundMessageId", ids[3], "cursor", latest.previousCursor())))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transactions.execute(status -> history.messages("window-owner", ids[0], "{\"conversationId\":" + ids[1] + ",\"limit\":51}")))
                .isInstanceOf(IllegalArgumentException.class);

        Long big = transactions.execute(status -> {
            var own = conversation("window-owner");
            for (int i = 0; i < 51; i++)
                message(own, "USER", "\u0001😀".repeat(4000));

            entityManager.flush();
            return own.getId();
        });
        var bounded = transactions.execute(status -> history.messages("window-owner", ids[0], "{\"conversationId\":" + big + ",\"limit\":50}"));
        assertThat(bounded.messages()).hasSize(50).allSatisfy(entry -> assertThat(entry.contentTruncated()).isTrue());
        assertThat(json.writeValueAsBytes(bounded).length).isLessThan(128 * 1024);
    }

    private AiMessage message(AiConversation conversation, String role, String content) {
        var message = new AiMessage(conversation, null, role, content);
        entityManager.persist(message);
        return message;
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
