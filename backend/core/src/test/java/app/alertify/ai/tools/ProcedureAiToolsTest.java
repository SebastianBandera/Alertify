package app.alertify.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import app.alertify.ai.AiToolExecutor;
import app.alertify.ai.AiToolSupport;
import app.alertify.ai.api.AiPage;
import app.alertify.ai.api.AiPageRequest;
import app.alertify.ai.template.AiTemplateSourceService;
import app.alertify.procedures.api.ProcedureResponse;
import app.alertify.procedures.service.ProcedureCatalogService;
import app.alertify.procedures.service.ProcedureCsvService;
import app.alertify.procedures.service.ProcedureExecutionQueryService;
import app.alertify.procedures.service.ProcedureManagementService;
import app.alertify.procedures.service.ProcedureTagService;

class ProcedureAiToolsTest {
    private final ProcedureManagementService procedures = mock(ProcedureManagementService.class);
    private final AiToolExecutor executor = mock(AiToolExecutor.class);
    private ProcedureAiTools tools;

    @BeforeEach
    void initializeTools() {
        tools = new ProcedureAiTools(procedures, mock(ProcedureCatalogService.class), mock(ProcedureCsvService.class),
                mock(ProcedureExecutionQueryService.class), mock(ProcedureTagService.class), mock(AiTemplateSourceService.class),
                executor, mock(AiToolSupport.class));
        when(executor.execute(eq("alertify_procedure_search"), org.mockito.ArgumentMatchers.<Supplier<AiPage<ProcedureResponse>>>any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        when(procedures.search(any(), any(), any(), anyBoolean(), any(Pageable.class))).thenReturn(Page.empty());
    }

    @Test
    void treatsZeroAndOmissionAsNoTemplateFilterWithSafeDefaults() {
        tools.search("fixture", 0L, null, null, null);
        var pagination = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(procedures).search(eq("fixture"), eq(null), eq(Set.of()), eq(false), pagination.capture());
        assertThat(pagination.getValue().getPageNumber()).isZero();
        assertThat(pagination.getValue().getPageSize()).isEqualTo(20);
        assertThat(pagination.getValue().getSort().getOrderFor("updatedAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        tools.search("omitted", null, null, null, null);
        verify(procedures).search(eq("omitted"), eq(null), eq(Set.of()), eq(false), any(Pageable.class));
    }

    @Test
    void preservesPositiveTemplateTagMatchAndExplicitPagination() {
        var page = new AiPageRequest(1, 5, "name", Sort.Direction.ASC);
        tools.search("fixture", 9L, List.of(12L), true, page);
        verify(procedures).search(eq("fixture"), eq(9L), eq(Set.of(12L)), eq(true), eq(page.pageable()));
    }

    @Test
    void rejectsNegativeTemplateBeforeExecution() {
        assertThatThrownBy(() -> tools.search("fixture", -1L, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(procedures);
    }
}
