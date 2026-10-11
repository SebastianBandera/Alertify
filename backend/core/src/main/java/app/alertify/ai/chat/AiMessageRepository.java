package app.alertify.ai.chat;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AiMessageRepository extends JpaRepository<AiMessage, Long> {
    List<AiMessage> findAllByConversation_IdOrderById(Long conversationId);
}
