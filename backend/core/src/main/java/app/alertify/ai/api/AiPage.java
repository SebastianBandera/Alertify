package app.alertify.ai.api;

import java.util.List;

import org.springframework.data.domain.Page;

/** Framework-neutral page returned by AI tools. */
public record AiPage<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> AiPage<T> from(Page<T> value) {
        return new AiPage<>(List.copyOf(value.getContent()), value.getNumber(), value.getSize(),
                value.getTotalElements(), value.getTotalPages());
    }
}
