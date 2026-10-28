package com.deductionaudit.agent;

import com.deductionaudit.agent.EvidenceDigestBuilder.DigestItem;
import com.deductionaudit.config.EvaluationProperties;
import com.deductionaudit.domain.Ask;
import com.deductionaudit.domain.Claim;
import com.deductionaudit.shared.ApiException;
import com.deductionaudit.trace.TraceEntry;
import com.deductionaudit.trace.TraceRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DraftingAgentTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String BOL = "BOL:SHP-DISPUTE";

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private static Claim claim() {
        return new Claim(ID, 1, "SHORTAGE", "480.00", "USD", Ask.REVERSE_DEDUCTION,
                List.of("EDI812:" + ID), List.of(BOL), List.of());
    }

    private static List<DigestItem> digest() {
        return List.of(new DigestItem(BOL, "BOL", Map.of("shippedQty", 120)));
    }

    private static String narrativeJson(String narrative) {
        return "{\"narrative\":\"" + narrative + "\"}";
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build();
    }

    private DraftingAgent agent(ChatModel model, TraceRecorder trace) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (model != null) {
            factory.registerSingleton("chatModel", model);
        }
        return new DraftingAgent(factory.getBeanProvider(ChatModel.class), trace, executor,
                new EvaluationProperties(null, null, Duration.ofMillis(100), null, 0, 0, 0, null),
                new PolicyProvider(), "test-model");
    }

    private static final class StubChatModel implements ChatModel {

        private final Answer answer;

        private StubChatModel(Answer answer) {
            this.answer = answer;
        }

        private static StubChatModel text(String text) {
            return new StubChatModel(() -> response(text));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            try {
                return answer.answer();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    private interface Answer {
        ChatResponse answer() throws Exception;
    }

    private static final class RecordingTrace implements TraceRecorder {

        private final List<TraceEntry> drafts = new ArrayList<>();

        @Override
        public void recordModelRound(UUID deductionId, int attemptNo, String model, String request,
                                     String response, Integer inputTokens, Integer outputTokens,
                                     long durationMs, String error) {
        }

        @Override
        public void recordToolCall(UUID deductionId, int attemptNo, String toolName, String arguments,
                                   String result, long durationMs, List<String> evidenceIds, String error) {
        }

        @Override
        public void recordDraft(UUID deductionId, int attemptNo, String model, String request,
                                String response, long durationMs, List<String> evidenceIds, String error) {
            drafts.add(new TraceEntry(deductionId, attemptNo, "draft", "drafter", Instant.now(), durationMs,
                    request, response, evidenceIds, null, null, error));
        }

        @Override
        public List<TraceEntry> read(UUID deductionId, int attemptNo) {
            return List.copyOf(drafts);
        }
    }

    @Test
    void anIdOutsideTheClosedSetFailsWithThatIdInTheMessage() {
        RecordingTrace trace = new RecordingTrace();
        DraftingAgent agent = agent(StubChatModel.text(narrativeJson("The goods arrived per POD:SHP-FORGED.")),
                trace);

        assertThatThrownBy(() -> agent.draft(claim(), digest()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(500);
                    assertThat(e.code()).isEqualTo("DRAFT_INCONSISTENT");
                    assertThat(e.getMessage()).contains("POD:SHP-FORGED");
                });
        assertThat(trace.drafts).hasSize(1);
        assertThat(trace.drafts.get(0).type()).isEqualTo("draft");
        assertThat(trace.drafts.get(0).name()).isEqualTo("drafter");
        assertThat(trace.drafts.get(0).response()).isNotNull();
        assertThat(trace.drafts.get(0).error()).isNull();
        assertThat(trace.drafts.get(0).evidenceIds()).containsExactly(BOL);
    }

    @Test
    void aNarrativeWithoutAnyEvidenceIdFails() {
        DraftingAgent agent = agent(StubChatModel.text(narrativeJson("The deduction is not supported.")),
                new RecordingTrace());

        assertThatThrownBy(() -> agent.draft(claim(), digest()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(500);
                    assertThat(e.code()).isEqualTo("DRAFT_INCONSISTENT");
                    assertThat(e.getMessage()).contains("no evidence id");
                });
    }

    @Test
    void aNarrativeStartingWithAMarkdownMarkerFails() {
        DraftingAgent agent = agent(StubChatModel.text(narrativeJson("## Notice\\n" + BOL + " is attached.")),
                new RecordingTrace());

        assertThatThrownBy(() -> agent.draft(claim(), digest()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(500);
                    assertThat(e.code()).isEqualTo("DRAFT_INCONSISTENT");
                    assertThat(e.getMessage()).contains("markdown");
                });
    }

    @Test
    void aNarrativeWithACodeFenceFails() {
        DraftingAgent agent = agent(StubChatModel.text(narrativeJson(BOL + " is attached. ```")),
                new RecordingTrace());

        assertThatThrownBy(() -> agent.draft(claim(), digest()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(500);
                    assertThat(e.code()).isEqualTo("DRAFT_INCONSISTENT");
                    assertThat(e.getMessage()).contains("code fence");
                });
    }

    @Test
    void aShortNarrativeStillSucceedsBecauseWordCountIsNotBlocking() {
        RecordingTrace trace = new RecordingTrace();
        String narrative = BOL + " proves this claim today.";
        DraftingAgent agent = agent(StubChatModel.text(narrativeJson(narrative)), trace);

        DraftingAgent.DraftResult result = agent.draft(claim(), digest());

        assertThat(result.narrative()).isEqualTo(narrative);
        assertThat(result.promptVersion()).hasSize(12);
        assertThat(result.model()).isEqualTo("test-model");
        assertThat(trace.drafts).hasSize(1);
    }

    @Test
    void aTimeoutIsADraftFailureWithAFailedTrace() {
        RecordingTrace trace = new RecordingTrace();
        DraftingAgent agent = agent(new StubChatModel(() -> {
            Thread.sleep(2_000);
            return response(narrativeJson(BOL + " is attached."));
        }), trace);

        assertThatThrownBy(() -> agent.draft(claim(), digest()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e.code()).isEqualTo("DRAFT_FAILED");
                });
        assertThat(trace.drafts).hasSize(1);
        assertThat(trace.drafts.get(0).response()).isNull();
        assertThat(trace.drafts.get(0).error()).isNotBlank();
    }

    @Test
    void aRateLimitedModelIsADraftFailureWithAFailedTrace() {
        RecordingTrace trace = new RecordingTrace();
        DraftingAgent agent = agent(new StubChatModel(() -> {
            throw new IllegalStateException("429 rate limited");
        }), trace);

        assertThatThrownBy(() -> agent.draft(claim(), digest()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e.code()).isEqualTo("DRAFT_FAILED");
                    assertThat(e.getMessage()).contains("429");
                });
        assertThat(trace.drafts).hasSize(1);
        assertThat(trace.drafts.get(0).response()).isNull();
        assertThat(trace.drafts.get(0).error()).contains("429");
    }

    @Test
    void noConfiguredModelIsADraftFailureWithAFailedTrace() {
        RecordingTrace trace = new RecordingTrace();
        DraftingAgent agent = agent(null, trace);

        assertThatThrownBy(() -> agent.draft(claim(), digest()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e.code()).isEqualTo("DRAFT_FAILED");
                });
        assertThat(trace.drafts).hasSize(1);
        assertThat(trace.drafts.get(0).response()).isNull();
        assertThat(trace.drafts.get(0).error()).isNotBlank();
    }

    @Test
    void invalidJsonIsADraftFailureWithAFailedTrace() {
        RecordingTrace trace = new RecordingTrace();
        DraftingAgent agent = agent(StubChatModel.text("this is not json"), trace);

        assertThatThrownBy(() -> agent.draft(claim(), digest()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e.code()).isEqualTo("DRAFT_FAILED");
                });
        assertThat(trace.drafts).hasSize(1);
        assertThat(trace.drafts.get(0).response()).isNull();
        assertThat(trace.drafts.get(0).error()).isNotBlank();
    }
}
