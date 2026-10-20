package app.alertify.ai.chat;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiMessageRepository extends JpaRepository<AiMessage, Long> {
    @EntityGraph(attributePaths = "turn")
    List<AiMessage> findAllByConversation_IdOrderById(Long conversationId);

    @EntityGraph(attributePaths = "conversation")
    @Query("""
            select m from AiMessage m where m.conversation.userSubject = :subject
              and m.conversation.id <> :currentConversationId and m.role in ('USER', 'ASSISTANT')
              and m.id < :beforeId and m.createdAt >= :from and m.createdAt <= :until
              and (locate(:query, lower(m.content)) > 0 or
                (locate(:query, lower(m.conversation.title)) > 0 and not exists
                  (select newer.id from AiMessage newer where newer.conversation = m.conversation
                    and newer.role in ('USER', 'ASSISTANT') and newer.id > m.id)))
            order by m.id desc
            """)
    List<AiMessage> searchHistory(@Param("subject") String subject, @Param("currentConversationId") Long currentConversationId, @Param("query") String query, @Param("from") Instant from, @Param("until") Instant until, @Param("beforeId") long beforeId, Pageable page);

    @Query("""
            select m from AiMessage m where m.conversation.userSubject = :subject
              and m.conversation.id = :conversationId and m.id = :messageId
              and m.role in ('USER', 'ASSISTANT')
            """)
    Optional<AiMessage> readableMessage(@Param("subject") String subject, @Param("conversationId") Long conversationId, @Param("messageId") long messageId);

    @Query("""
            select m from AiMessage m where m.conversation.userSubject = :subject
              and m.conversation.id = :conversationId and m.role in ('USER', 'ASSISTANT')
              and m.id < :beforeId order by m.id desc
            """)
    List<AiMessage> messagesBefore(@Param("subject") String subject, @Param("conversationId") Long conversationId, @Param("beforeId") long beforeId, Pageable page);

    @Query("""
            select m from AiMessage m where m.conversation.userSubject = :subject
              and m.conversation.id = :conversationId and m.role in ('USER', 'ASSISTANT')
              and m.id > :afterId order by m.id asc
            """)
    List<AiMessage> messagesAfter(@Param("subject") String subject, @Param("conversationId") Long conversationId, @Param("afterId") long afterId, Pageable page);
}
