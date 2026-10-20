package app.alertify.playwright;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WizardDraftRepository extends JpaRepository<WizardDraft, UUID> {
    Optional<WizardDraft> findByIdAndOwnerSubject(UUID id, String owner);
    List<WizardDraft> findAllByOwnerSubjectOrderByUpdatedAtDesc(String owner);
}
