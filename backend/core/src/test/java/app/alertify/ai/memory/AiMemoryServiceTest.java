package app.alertify.ai.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import app.alertify.ai.chat.AiConversationRepository;
import app.alertify.logging.ApplicationEventLogger;
import tools.jackson.databind.json.JsonMapper;

class AiMemoryServiceTest {
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private final AiMemoryRepository repository = mock(AiMemoryRepository.class);
    private final AiMemoryService service = new AiMemoryService(repository, mock(AiConversationRepository.class), mock(ApplicationEventLogger.class), JSON);

    @Test
    void rejectsInvalidKeysContentVersionsLimitsAndMissingIdentityBeforeQuerying() {
        for (String key : List.of("", "Other User", "../file", "UPPERCASE", "a".repeat(97)))
            assertThatThrownBy(() -> service.save("owner", null, key, "content", null)).isInstanceOf(IllegalArgumentException.class);

        for (String content : List.of("", " ", "a".repeat(4001), "contains\0NUL"))
            assertThatThrownBy(() -> service.save("owner", null, "valid.key", content, null)).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.save("owner", null, "valid.key", "content", -1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(null, "", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search("owner", null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search("owner", "", 21, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search("owner", "", 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search("owner", "", 1, "not-a-cursor")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void indexesOnlyKeysAndDatesWithinTheContextBudgetAndFlagsMissingEntries() {
        List<AiMemoryRepository.IndexView> entries = IntStream.range(0, 51).mapToObj(index -> {
            var entry = mock(AiMemoryRepository.IndexView.class);
            when(entry.getMemoryKey()).thenReturn("k".repeat(91) + String.format("%05d", index));
            when(entry.getUpdatedAt()).thenReturn(Instant.parse("2026-10-17T12:00:00Z"));
            return entry;
        }).toList();
        when(repository.findByUserSubjectOrderByMemoryKeyAsc(eq("owner"), any())).thenReturn(entries);

        var index = service.index("owner");

        assertThat(index.entries().size()).isBetween(1, 50);
        assertThat(index.incomplete()).isTrue();
        assertThat(JSON.writeValueAsBytes(index).length).isLessThanOrEqualTo(8 * 1024);
        assertThat(JSON.readTree(JSON.writeValueAsString(index)).path("entries").get(0).propertyNames())
                .containsExactlyInAnyOrder("key", "updatedAt");
        verify(repository).findByUserSubjectOrderByMemoryKeyAsc(eq("owner"), argThat(page -> page.getPageSize() == 51));
    }

    @Test
    void capsSerializedSearchPagesWithoutTruncatingContentOrSkippingTheCursorBoundary() {
        var entries = IntStream.range(0, 21)
                .mapToObj(index -> new AiMemory("owner", "entry." + String.format("%03d", index), "\u0001".repeat(4000), null)).toList();
        when(repository.search(eq("owner"), eq(""), eq(""), any(Pageable.class))).thenReturn(entries);
        var result = service.search("owner", "", 20, null);
        assertThat(result.memories().size()).isBetween(1, 19);
        assertThat(result.memories()).allSatisfy(memory -> assertThat(memory.content()).hasSize(4000));
        assertThat(result.nextCursor()).isNotBlank();
        assertThat(JSON.writeValueAsBytes(result).length).isLessThan(128 * 1024);
        String lastKey = result.memories().getLast().key();
        service.search("owner", "", 20, result.nextCursor());
        verify(repository).search(eq("owner"), eq(""), eq(lastKey), any());
        assertThatThrownBy(() -> service.search("someone-else", "", 20, result.nextCursor())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search("owner", "changed query", 20, result.nextCursor())).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).search(eq("someone-else"), any(), any(), any());
    }
}
