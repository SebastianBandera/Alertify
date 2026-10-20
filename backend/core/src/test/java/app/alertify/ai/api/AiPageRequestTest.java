package app.alertify.ai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class AiPageRequestTest {

    @Test
    void capsAnOversizedModelRequestAtTheMaximumPageSize() {
        AiPageRequest request = new AiPageRequest(0, 100, "name", Sort.Direction.ASC);

        assertThat(request.size()).isEqualTo(AiPageRequest.MAX_SIZE);
        assertThat(request.pageable().getPageSize()).isEqualTo(AiPageRequest.MAX_SIZE);
    }

    @Test
    void stillRejectsNonPositivePageSizes() {
        assertThatThrownBy(() -> new AiPageRequest(0, 0, "name", Sort.Direction.ASC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Page size must be one or greater");
    }
}
