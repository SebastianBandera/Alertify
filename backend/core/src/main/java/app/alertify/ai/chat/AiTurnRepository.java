package app.alertify.ai.chat;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AiTurnRepository extends JpaRepository<AiTurn, Long> {
    List<AiTurn> findAllByConversation_IdOrderById(Long conversationId);
    List<AiTurn> findAllByStatusIn(List<String> statuses);
    Optional<AiTurn> findByIdAndConversation_UserSubject(Long id, String userSubject);
}
