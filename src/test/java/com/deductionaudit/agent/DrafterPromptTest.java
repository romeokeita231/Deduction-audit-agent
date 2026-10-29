package com.deductionaudit.agent;

import com.deductionaudit.agent.EvidenceDigestBuilder.DigestItem;
import com.deductionaudit.agent.validators.DeductionValidator;
import com.deductionaudit.config.EvaluationProperties;
import com.deductionaudit.domain.Ask;
import com.deductionaudit.domain.Claim;
import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.DeductionCategory;
import com.deductionaudit.shared.PromptMarker;
import com.deductionaudit.trace.TraceEntry;
import com.deductionaudit.trace.TraceRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class DrafterPromptTest {

    private final PolicyProvider policy = new PolicyProvider();
    private final DraftingAgent agent = new DraftingAgent(
            new DefaultListableBeanFactory().getBeanProvider(ChatModel.class),
            null, null, new EvaluationProperties(null, null, null, null, 0, 0, 0, null),
            policy, "test-model");

    private static final class StubValidator implements DeductionValidator {

        @Override
        public DeductionCategory category() {
            return DeductionCategory.SHORTAGE;
        }

        @Override
        public List<String> allowedTools() {
            return List.of();
        }

        @Override
        public String systemPrompt(String policyText) {
            return "You audit shortages.\n" + policyText;
        }

        @Override
        public String userPrompt(Deduction deduction) {
            return "";
        }
    }

    @Test
    void theContractReachesTheSentString() {
        assertThat(agent.systemMessage()).contains(DraftingAgent.NARRATIVE_CONTRACT.strip());
    }

    @Test
    void theSkeletonNamesAllFourParts() {
        String message = agent.systemMessage();
        assertThat(message).contains(DraftingAgent.SKELETON.strip());
        assertThat(message).contains("what the deduction is");
        assertThat(message).contains("the amount at stake");
        assertThat(message).contains("not supported");
        assertThat(message).contains("the basis");
        assertThat(message).contains("reverse the deduction");
        assertThat(message).contains("credit memo");
        assertThat(message).contains("Close");
    }

    @Test
    void theContractStatesTheAmountOnlyOnce() {
        assertThat(agent.systemMessage()).contains("state the amount once in the opening paragraph");
        assertThat(agent.systemMessage()).contains("do not restate it in later paragraphs");
    }

    @Test
    void thePolicyTextIsIncludedInFull() {
        assertThat(agent.systemMessage()).contains(policy.text());
    }

    @Test
    void theMarkerIsTheLastThingInTheMessage() {
        assertThat(agent.systemMessage()).endsWith(PromptMarker.DRAFT);
    }

    @Test
    void everyDraftOutputComponentIsNamedInTheContract() {
        RecordComponent[] components = DraftOutput.class.getRecordComponents();
        assertThat(components).hasSize(1);
        assertThat(components[0].getName()).isEqualTo("narrative");
        assertThat(Arrays.stream(components).map(RecordComponent::getName))
                .allSatisfy(name -> assertThat(DraftingAgent.NARRATIVE_CONTRACT).contains(name));
    }

    @Test
    void theVersionHashesTheWholeSystemMessage() {
        String version = agent.promptVersion();
        assertThat(version).hasSize(12);
        assertThat(version).isEqualTo(PolicyProvider.sha256(agent.systemMessage()).substring(0, 12));
        assertThat(version)
                .isNotEqualTo(PolicyProvider.sha256(policy.text()).substring(0, 12));

        EvaluationAgent evaluation = new EvaluationAgent(
                new DefaultListableBeanFactory().getBeanProvider(ChatModel.class),
                null, null, new EvaluationProperties(null, null, null, null, 0, 0, 0, null),
                policy, null, "test-model");
        assertThat(version).isNotEqualTo(evaluation.promptVersion(new StubValidator()));
    }

    @Test
    void theContractIsInThePromptHandedToTheModel() {
        List<Prompt> sent = new ArrayList<>();
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("chatModel", new CapturingChatModel(sent));
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            DraftingAgent drafting = new DraftingAgent(factory.getBeanProvider(ChatModel.class),
                    new NoopTrace(), executor,
                    new EvaluationProperties(null, null, Duration.ofSeconds(5), null, 0, 0, 0, null),
                    policy, "test-model");
            drafting.draft(claim(), List.of(new DigestItem("POD:SHP-DISPUTE", "POD", Map.of())));
        } finally {
            executor.shutdownNow();
        }

        assertThat(sent).hasSize(1);
        List<Message> instructions = sent.get(0).getInstructions();
        assertThat(instructions.get(0)).isInstanceOf(SystemMessage.class);
        String system = instructions.get(0).getText();
        assertThat(system).contains(DraftingAgent.NARRATIVE_CONTRACT.strip());
        assertThat(system).endsWith(PromptMarker.DRAFT);
    }

    private static Claim claim() {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        return new Claim(id, 1, "SHORTAGE", "480.00", "USD", Ask.REVERSE_DEDUCTION,
                List.of("EDI812:" + id), List.of("POD:SHP-DISPUTE"), List.of());
    }

    private static final class CapturingChatModel implements ChatModel {

        private final List<Prompt> sent;

        private CapturingChatModel(List<Prompt> sent) {
            this.sent = sent;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            sent.add(prompt);
            return ChatResponse.builder()
                    .generations(List.of(new Generation(new AssistantMessage(
                            "{\"narrative\":\"POD:SHP-DISPUTE was accepted in full.\"}"))))
                    .build();
        }
    }

    private static final class NoopTrace implements TraceRecorder {

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
                                String response, Integer inputTokens, Integer outputTokens, long durationMs,
                                List<String> evidenceIds, String error) {
        }

        @Override
        public List<TraceEntry> read(UUID deductionId, int attemptNo) {
            return List.of();
        }
    }
}
