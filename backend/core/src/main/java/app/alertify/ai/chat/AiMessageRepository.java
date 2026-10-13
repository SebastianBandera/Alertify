package app.alertify.ai.chat;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

public interface AiMessageRepository extends JpaRepository<AiMessage, Long> {
    @EntityGraph(attributePaths = "turn")
    List<AiMessage> findAllByConversation_IdOrderById(Long conversationId);
}
