package app.alertify.pipes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.api.error.ConflictException;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.jpa.entity.Tag;
import app.alertify.jpa.entity.TagScope;
import app.alertify.jpa.repository.PipeRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.logging.ApplicationEventLogger;

class PipeTagServiceTest {
    private final TagRepository tags = mock(TagRepository.class);
    private final PipeRepository pipes = mock(PipeRepository.class);
    private final PipeTagService service = new PipeTagService(tags, pipes, mock(ApplicationEventLogger.class));

    @Test
    void readsOnlyThePipeScopedTagNamespace() {
        Tag tag = new Tag(TagScope.PIPE, "QA-TAG", "#123456");
        ReflectionTestUtils.setField(tag, "id", 94L);
        when(tags.findByIdAndScope(94L, TagScope.PIPE)).thenReturn(Optional.of(tag));
        var result = service.get(94L);
        assertThat(result.id()).isEqualTo(94L);
        assertThat(result.scope()).isEqualTo(TagScope.PIPE);
        verify(tags).findByIdAndScope(94L, TagScope.PIPE);
        verifyNoInteractions(pipes);
    }

    @Test
    void doesNotSubstituteAPipeOrATagInAnotherScopeWhenMissing() {
        when(tags.findByIdAndScope(94L, TagScope.PIPE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(94L)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(pipes);
        verify(tags, never()).findById(94L);
    }

    @Test
    void keepsAssignedTagDeletionProtected() {
        Tag tag = new Tag(TagScope.PIPE, "QA-TAG", "#123456");
        when(tags.findByIdAndScope(94L, TagScope.PIPE)).thenReturn(Optional.of(tag));
        when(pipes.existsByTagsId(94L)).thenReturn(true);
        assertThatThrownBy(() -> service.delete(94L, 0)).isInstanceOf(ConflictException.class);
        verify(tags, never()).delete(tag);
    }
}
