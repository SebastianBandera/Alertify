package app.alertify.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.http.MediaType;

import app.alertify.ai.AiToolCatalog;
import app.alertify.ai.localization.AiLocalizationSourceService;
import app.alertify.api.error.InvalidAiChatReasoningEffortException;
import app.alertify.api.error.ApiExceptionHandler;
import app.alertify.codex.AiWorkerClient;
import app.alertify.codex.AiModuleService;
import app.alertify.controller.AiModuleController;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.realtime.AdminEventPublisher;
import app.alertify.worker.ai.grpc.*;
import tools.jackson.databind.json.JsonMapper;

class AiChatReasoningEffortTest {
    private static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh");
    private static final String UPGRADE_TOOL = "alertify_request_reasoning_upgrade";

    @ParameterizedTest
    @ValueSource(strings = { "high", "xhigh", "", "invalid" })
    void apiReturns400ForDisallowedEffortsWithoutStartingATurn(String requested) throws Exception {
        var fixture = new Fixture("medium");
        var mvc = MockMvcBuilders.standaloneSetup(new AiModuleController(mock(AiModuleService.class), fixture.service,
                mock(app.alertify.ai.memory.AiMemoryManagementService.class)))
                .setControllerAdvice(new ApiExceptionHandler(mock(ApplicationEventLogger.class))).build();
        var response = mvc.perform(MockMvcRequestBuilders.post("/api/ai/chat/turns").principal(fixture.authentication)
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"message\",\"reasoningEffort\":\"" + requested + "\"}"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(JsonMapper.builder().build().readTree(response.getContentAsString()).path("code").stringValue())
                .isEqualTo("AI_CHAT_REASONING_EFFORT_INVALID");
        verifyNoInteractions(fixture.persistence);
        verify(fixture.worker, never()).chat(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = { "{\"message\":\"message\"}", "{\"message\":\"message\",\"reasoningEffort\":null}" })
    void apiAcceptsLegacyRequestsAndDefaultsToLow(String body) throws Exception {
        var fixture = new Fixture("medium");
        var mvc = MockMvcBuilders.standaloneSetup(new AiModuleController(mock(AiModuleService.class), fixture.service,
                mock(app.alertify.ai.memory.AiMemoryManagementService.class)))
                .setControllerAdvice(new ApiExceptionHandler(mock(ApplicationEventLogger.class))).build();
        var response = mvc.perform(MockMvcRequestBuilders.post("/api/ai/chat/turns").principal(fixture.authentication)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(fixture.starts().getFirst().getReasoningEffort()).isEqualTo("low");
    }

    @ParameterizedTest
    @MethodSource("effortPairs")
    void enforcesTheSystemMaximumBeforePersistingOrOpeningAStream(String maximum, String requested) {
        var fixture = new Fixture(maximum);
        if (EFFORTS.indexOf(requested) > EFFORTS.indexOf(maximum)) {
            assertThatThrownBy(() -> fixture.submit(requested)).isInstanceOf(InvalidAiChatReasoningEffortException.class);
            verifyNoInteractions(fixture.persistence);
            verify(fixture.worker, never()).chat(any(), any(), any());
            return;
        }

        fixture.submit(requested);
        verify(fixture.persistence).start("subject-1", "subject-1", null, "message", "READ_ONLY", "ASK", "configured-model", requested);
        assertThat(fixture.starts()).singleElement().satisfies(start -> {
            assertThat(start.getReasoningEffort()).isEqualTo(requested);
            assertThat(start.getModel()).isEqualTo("configured-model");
        });
    }

    static Stream<Arguments> effortPairs() {
        return EFFORTS.stream().flatMap(maximum -> EFFORTS.stream().map(requested -> Arguments.of(maximum, requested)));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "low" })
    void defaultsToLowRegardlessOfTheSystemMaximum(String requested) {
        var fixture = new Fixture("xhigh");
        fixture.submit(requested);
        assertThat(fixture.starts().getFirst().getReasoningEffort()).isEqualTo("low");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", "LOW", " medium", "none", "invalid" })
    void rejectsMalformedValuesWithoutSideEffects(String requested) {
        var fixture = new Fixture("xhigh");
        assertThatThrownBy(() -> fixture.submit(requested)).isInstanceOf(InvalidAiChatReasoningEffortException.class);
        verifyNoInteractions(fixture.persistence);
        verify(fixture.worker, never()).chat(any(), any(), any());
    }

    @Test
    void upgradesOnlyOnceAndRemovesTheToolFromTheRestartedStream() {
        var fixture = new Fixture("xhigh");
        fixture.submit("low");
        assertThat(fixture.starts().getFirst().getToolsList()).anyMatch(tool -> UPGRADE_TOOL.equals(tool.getName()));
        fixture.upgrade();
        assertThat(fixture.starts()).extracting(ChatStart::getReasoningEffort).containsExactly("low", "medium");
        assertThat(fixture.starts().getLast().getToolsList()).noneMatch(tool -> UPGRADE_TOOL.equals(tool.getName()));
        verify(fixture.persistence).upgraded(8L, "medium");
        fixture.upgrade();
        fixture.assertUpgradeDenied();
    }

    @ParameterizedTest
    @ValueSource(strings = { "low", "medium", "high", "xhigh" })
    void neverOffersOrExecutesAnUpgradeAtTheMaximum(String maximum) {
        var fixture = new Fixture(maximum);
        fixture.submit(maximum);
        assertThat(fixture.starts().getFirst().getToolsList()).noneMatch(tool -> UPGRADE_TOOL.equals(tool.getName()));
        fixture.upgrade();
        fixture.assertUpgradeDenied();
        assertThat(fixture.starts()).hasSize(1);
    }

    @Test
    void rereadsTheMaximumBeforeAnUpgrade() {
        var fixture = new Fixture("medium");
        fixture.submit("low");
        fixture.setMaximum("low");
        fixture.upgrade();
        fixture.assertUpgradeDenied();
        assertThat(fixture.starts()).hasSize(1);
    }

    @Test
    void increasingTheMaximumDoesNotRaiseAnExistingTurnsCeiling() {
        var fixture = new Fixture("medium");
        fixture.submit("medium");
        fixture.setMaximum("high");
        fixture.upgrade();
        fixture.assertUpgradeDenied();
        assertThat(fixture.starts()).hasSize(1);
    }

    @Test
    void unavailableSettingsDenyAnUpgradeWithoutLeakingDetails() {
        var fixture = new Fixture("medium");
        fixture.submit("low");
        when(fixture.worker.settings()).thenThrow(new IllegalStateException("PRIVATE-SETTINGS-ERROR"));
        fixture.upgrade();
        fixture.assertUpgradeDenied();
        assertThat(fixture.sent.getLast().getToolResult().getOutput()).doesNotContain("PRIVATE");
        assertThat(fixture.starts()).hasSize(1);
    }

    private static final class Fixture {
        private final AiWorkerClient worker = mock(AiWorkerClient.class);
        private final AiChatPersistence persistence = mock(AiChatPersistence.class);
        private final Authentication authentication = mock(Authentication.class);
        private final AtomicReference<AiSettings> settings = new AtomicReference<>();
        private final List<ChatClientMessage> sent = new ArrayList<>();
        private final List<Consumer<ChatWorkerMessage>> consumers = new ArrayList<>();
        private final AiChatService service;

        private Fixture(String maximum) {
            setMaximum(maximum);
            when(authentication.getName()).thenReturn("subject-1");
            when(worker.state()).thenReturn(ModuleStateResponse.newBuilder().setEnabled(true).setSessionStatus("ACTIVE").build());
            when(worker.settings()).thenAnswer(call -> settings.get());
            when(persistence.start(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(new AiChatPersistence.StartedTurn(7L, 8L));
            when(persistence.tool(any(), anyLong(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
                    anyBoolean(), anyBoolean(), anyBoolean(), any(), anyBoolean())).thenAnswer(call -> {
                        var turn = mock(AiTurn.class);
                        when(turn.getId()).thenReturn(8L);
                        var invocation = new AiToolInvocation(turn, call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4),
                                call.getArgument(5), call.getArgument(6), call.getArgument(7), call.getArgument(8), call.getArgument(9),
                                call.getArgument(10), call.getArgument(11), call.getArgument(12), call.getArgument(13));
                        ReflectionTestUtils.setField(invocation, "id", 99L);
                        return invocation;
                    });
            when(worker.chat(any(), any(), any())).thenAnswer(call -> {
                consumers.add(call.getArgument(0));
                var handle = mock(AiWorkerClient.ChatHandle.class);
                doAnswer(message -> { sent.add(message.getArgument(0)); return null; }).when(handle).send(any());
                return handle;
            });
            var localization = mock(AiLocalizationSourceService.class);
            when(localization.resolveLocale(any())).thenReturn("en");
            service = new AiChatService(worker, mock(AiToolCatalog.class), persistence, mock(AiConversationRepository.class), mock(AiTurnRepository.class),
                    mock(AiMessageRepository.class), mock(AiToolInvocationRepository.class), localization, mock(AdminEventPublisher.class),
                    mock(ApplicationEventLogger.class), JsonMapper.builder().findAndAddModules().build(), mock(AiConversationHistory.class), mock(app.alertify.ai.memory.AiMemoryService.class));
        }

        private void setMaximum(String maximum) {
            settings.set(AiSettings.newBuilder().setModel("configured-model").setReasoningEffort(maximum).build());
        }

        private void submit(String effort) {
            service.submit(authentication, new AiChatService.SubmitRequest(null, "message", "READ_ONLY", "ASK", "en", effort));
        }

        private List<ChatStart> starts() {
            return sent.stream().filter(ChatClientMessage::hasStart).map(ChatClientMessage::getStart).toList();
        }

        private void upgrade() {
            consumers.getLast().accept(ChatWorkerMessage.newBuilder().setToolCall(ChatToolCall.newBuilder().setCallId("upgrade")
                    .setName(UPGRADE_TOOL).setArguments("{\"reason\":\"complex request\"}")).build());
        }

        private void assertUpgradeDenied() {
            var result = sent.getLast().getToolResult();
            assertThat(result.getFailed()).isTrue();
            assertThat(result.getDiagnosticCode()).isEqualTo("REASONING_UPGRADE_UNAVAILABLE");
        }
    }
}
