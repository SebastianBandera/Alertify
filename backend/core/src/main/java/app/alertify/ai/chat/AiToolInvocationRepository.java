package app.alertify.ai.chat;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiToolInvocationRepository extends JpaRepository<AiToolInvocation, Long> {
    List<AiToolInvocation> findAllByTurn_IdOrderById(Long turnId);
    List<AiToolInvocation> findAllByTurn_IdAndStatusIn(Long turnId, List<String> statuses);
    Optional<AiToolInvocation> findByIdAndTurn_Conversation_UserSubject(Long id, String userSubject);

    @Query("""
            SELECT invocation
            FROM AiToolInvocation invocation
            JOIN FETCH invocation.turn turn
            WHERE turn.conversation.id = :conversationId
              AND turn.id <= :throughTurnId
              AND invocation.kind = 'DOMAIN'
              AND (:beforeId = 0 OR invocation.id < :beforeId)
            ORDER BY invocation.id DESC
            """)
    List<AiToolInvocation> findOperationHistory(@Param("conversationId") Long conversationId, @Param("throughTurnId") Long throughTurnId, @Param("beforeId") long beforeId, Pageable pageable);

    @Query("""
            SELECT invocation
            FROM AiToolInvocation invocation
            WHERE invocation.turn.conversation.id = :conversationId
              AND invocation.turn.id <= :throughTurnId
              AND invocation.kind = 'DOMAIN'
              AND invocation.status = 'SUCCEEDED'
              AND invocation.result IS NOT NULL
              AND (
                :query = ''
                OR lower(invocation.toolName) LIKE concat('%', concat(:query, '%'))
                OR lower(invocation.arguments) LIKE concat('%', concat(:query, '%'))
                OR lower(invocation.result) LIKE concat('%', concat(:query, '%'))
              )
            ORDER BY invocation.id DESC
            """)
    List<AiToolInvocation> findRestorationHistory(@Param("conversationId") Long conversationId,
            @Param("throughTurnId") Long throughTurnId, @Param("query") String query, Pageable pageable);
}
