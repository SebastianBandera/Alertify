package app.alertify.playwright;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import app.alertify.api.error.ResourceNotFoundException;

@Service
public class WizardDraftStore {
    private final WizardDraftRepository repository;
    public WizardDraftStore(WizardDraftRepository repository) { this.repository = repository; }

    @Transactional
    public WizardDraft create(String owner, String document) { return repository.save(new WizardDraft(owner, document)); }

    @Transactional(readOnly = true)
    public WizardDraft load(String owner, UUID id) {
        return repository.findByIdAndOwnerSubject(id, owner).orElseThrow(() -> new ResourceNotFoundException("Wizard draft not found"));
    }

    @Transactional(readOnly = true)
    public List<WizardDraft> list(String owner) { return repository.findAllByOwnerSubjectOrderByUpdatedAtDesc(owner); }

    @Transactional
    public void save(String owner, UUID id, String document) { load(owner, id).update(document); }

    @Transactional
    public void delete(String owner, UUID id) { repository.delete(load(owner, id)); }
}
