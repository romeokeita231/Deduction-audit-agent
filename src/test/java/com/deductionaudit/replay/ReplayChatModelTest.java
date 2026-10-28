package com.deductionaudit.replay;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayChatModelTest {

    private static final String SHORTAGE = "11111111-1111-1111-1111-111111111111";
    private static final String RETRY = "cccccccc-cccc-cccc-cccc-cccccccccccc";

    private final ReplayChatModel model =
            new ReplayChatModel(new DefaultResourceLoader(), new MockEnvironment());

    private static Prompt prompt(String deductionId) {
        return new Prompt(List.of(new UserMessage("EDI812:" + deductionId)));
    }

    private static Prompt promptAfterAssistants(String deductionId, int assistantMessages) {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("EDI812:" + deductionId));
        for (int i = 0; i < assistantMessages; i++) {
            messages.add(AssistantMessage.builder().content("round " + i).build());
        }
        return new Prompt(messages);
    }

    @Test
    void theFirstRoundAsksForToolsAndTheSecondAnswers() {
        ChatResponse first = model.call(prompt(SHORTAGE));
        assertThat(first.hasToolCalls()).isTrue();
        assertThat(first.getResult().getOutput().getToolCalls())
                .extracting(AssistantMessage.ToolCall::name)
                .containsExactly("getAsn", "getBol", "getPod");

        ChatResponse second = model.call(promptAfterAssistants(SHORTAGE, 1));
        assertThat(second.hasToolCalls()).isFalse();
        assertThat(second.getResult().getOutput().getText()).contains("\"verdict\":\"DISPUTABLE\"");
    }

    @Test
    void itRefusesARoundTheScenarioDoesNotDeclare() {
        assertThat(model.call(prompt(SHORTAGE)).hasToolCalls()).isTrue();
        assertThatThrownBy(() -> model.call(promptAfterAssistants(SHORTAGE, 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no replay round 2");
    }

    @Test
    void anErroredAttemptIsFollowedByANewAttempt() {
        assertThatThrownBy(() -> model.call(prompt(RETRY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("429");

        ChatResponse retried = model.call(prompt(RETRY));
        assertThat(retried.hasToolCalls()).isTrue();
    }

    @Test
    void anUnknownDeductionFailsLoudly() {
        assertThatThrownBy(() -> model.call(prompt("00000000-0000-0000-0000-000000000000")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no replay scenario");
    }

    @Test
    void aPromptWithoutTheDeductionIdFailsLoudly() {
        assertThatThrownBy(() -> model.call(new Prompt(List.of(new UserMessage("no id here")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no deduction id");
    }
}
