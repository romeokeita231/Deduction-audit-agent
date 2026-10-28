package com.deductionaudit.agent;

import com.deductionaudit.agent.validators.DeductionValidator;
import com.deductionaudit.config.EvaluationProperties;
import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.DeductionCategory;
import com.deductionaudit.shared.PromptMarker;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

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
}
