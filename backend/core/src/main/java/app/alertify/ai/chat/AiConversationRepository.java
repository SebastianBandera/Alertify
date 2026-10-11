package app.alertify.ai.chat;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {
    List<AiConversation> findAllByOrderByUpdatedAtDescIdDesc();
    List<AiConversation> findAllByUserSubjectOrderByUpdatedAtDescIdDesc(String userSubject);
    Optional<AiConversation> findByIdAndUserSubject(Long id, String userSubject);
}
