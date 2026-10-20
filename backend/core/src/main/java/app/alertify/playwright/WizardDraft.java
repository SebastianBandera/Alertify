package app.alertify.playwright;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "playwright_wizard_drafts", schema = "core")
public class WizardDraft {
    @Id
    private UUID id;
    @Column(name = "owner_subject", nullable = false)
    private String ownerSubject;
    @Version
    private long version;
    @Column(nullable = false, columnDefinition = "text")
    private String document;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WizardDraft() { }

    WizardDraft(String owner, String document) {
        id = UUID.randomUUID();
        ownerSubject = owner;
        update(document);
    }

    void update(String value) { document = value; updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public String getOwnerSubject() { return ownerSubject; }
    public String getDocument() { return document; }
    public Instant getUpdatedAt() { return updatedAt; }
}
