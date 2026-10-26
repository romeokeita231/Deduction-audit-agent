package com.deductionaudit.agent;

import com.deductionaudit.agent.EvaluationAgent;
import com.deductionaudit.agent.validators.DeductionValidator;
import com.deductionaudit.config.EvaluationProperties;
import com.deductionaudit.domain.AgentVerdict;
import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.DeductionCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationAgentPromptTest {

    private final PolicyProvider policy = new PolicyProvider();
    private final EvaluationAgent agent = new EvaluationAgent(
            new DefaultListableBeanFactory().getBeanProvider(org.springframework.ai.chat.model.ChatModel.class),
            null, null, new EvaluationProperties(null, null, null, null, 0, 0, 0, null),
            policy, null, "test-model");

    private static final class StubValidator implements DeductionValidator {

        private final DeductionCategory category;
        private final String prompt;

        private StubValidator(DeductionCategory category, String prompt) {
            this.category = category;
            this.prompt = prompt;
        }

        @Override
        public DeductionCategory category() {
            return category;
        }

        @Override
        public List<String> allowedTools() {
            return List.of();
        }

        @Override
        public String systemPrompt(String policyText) {
            return prompt + policyText;
        }

        @Override
        public String userPrompt(Deduction deduction) {
            return "";
        }
    }

    private final StubValidator shortage =
            new StubValidator(DeductionCategory.SHORTAGE, "You audit shortages.\n");
    private final StubValidator price =
            new StubValidator(DeductionCategory.PRICE_VARIANCE, "You audit price variances.\n");

    @Test
    void theContractIsAppendedNotReplaced() {
        assertThat(agent.systemMessage(shortage)).startsWith(shortage.systemPrompt(policy.text()));
        assertThat(agent.systemMessage(price)).startsWith(price.systemPrompt(policy.text()));
    }

    @Test
    void theContractReachesTheSentString() {
        assertThat(agent.systemMessage(shortage)).contains(EvaluationAgent.OUTPUT_CONTRACT.strip());
        assertThat(agent.systemMessage(price)).contains(EvaluationAgent.OUTPUT_CONTRACT.strip());
    }

    @Test
    void everyVerdictComponentIsNamedInTheContract() {
        RecordComponent[] components = AgentVerdict.class.getRecordComponents();
        assertThat(components).isNotEmpty();
        Arrays.stream(components).forEach(component ->
                assertThat(EvaluationAgent.OUTPUT_CONTRACT)
                        .as("contract names the %s component", component.getName())
                        .contains(component.getName()));
    }

    @Test
    void theVersionHashesTheWholeSystemMessage() {
        String shortageVersion = agent.promptVersion(shortage);
        String priceVersion = agent.promptVersion(price);
        assertThat(shortageVersion).isNotEqualTo(priceVersion);
        assertThat(agent.promptVersion(shortage)).isEqualTo(shortageVersion);
        assertThat(shortageVersion).hasSize(12);
        assertThat(priceVersion).hasSize(12);
    }
}
