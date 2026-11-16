package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class WizardDraftStoreTest {
    @Test
    void compareAndSaveRejectsAStaleSnapshotWithoutOverwritingTheNewerDocument() {
        var repository = mock(WizardDraftRepository.class);
        var store = new WizardDraftStore(repository);
        var draft = new WizardDraft("owner", "newer-document");
        ReflectionTestUtils.setField(draft, "version", 2L);
        when(repository.findByIdAndOwnerSubject(draft.getId(), "owner")).thenReturn(Optional.of(draft));
        assertThatThrownBy(() -> store.compareAndSave("owner", draft.getId(), 1L, "stale-document")).hasMessageContaining("revision changed");
        assertThat(draft.getDocument()).isEqualTo("newer-document");
        verify(repository, never()).flush();
        store.compareAndSave("owner", draft.getId(), 2L, "accepted-document");
        assertThat(draft.getDocument()).isEqualTo("accepted-document");
        verify(repository).flush();
    }
}
