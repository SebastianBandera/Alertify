package app.alertify.ai.chat;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {
    List<AiConversation> findAllByOrderByUpdatedAtDescIdDesc();
    List<AiConversation> findAllByUserSubjectOrderByUpdatedAtDescIdDesc(String userSubject);
    Optional<AiConversation> findByIdAndUserSubject(Long id, String userSubject);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT conversation FROM AiConversation conversation WHERE conversation.id = :id AND conversation.userSubject = :subject")
    Optional<AiConversation> findLocked(@Param("id") Long id, @Param("subject") String subject);
}
