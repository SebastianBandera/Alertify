package app.alertify.dashboard;

import java.util.List;

import org.springframework.data.domain.Page;

/** One page of dashboard tiles, shaped like the paged REST responses the frontend already consumes. */
public record DashboardPageResponse(List<DashboardCardResponse> content, PageMetadata page) {

    public static DashboardPageResponse of(Page<?> page, List<DashboardCardResponse> content) {
        return new DashboardPageResponse(content, new PageMetadata(page.getSize(), page.getNumber(), page.getTotalElements(), page.getTotalPages()));
    }

    public record PageMetadata(int size, int number, long totalElements, int totalPages) {
    }
}
