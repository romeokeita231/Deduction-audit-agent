package com.deductionaudit.replay;

import com.deductionaudit.shared.PromptInspection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Profile("replay")
public class ReplayChatModel implements ChatModel {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ReplayScenario verdicts;
    private final ReplayScenario drafts;
    private final Map<UUID, AtomicInteger> attemptCounters = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> draftCounters = new ConcurrentHashMap<>();
    private final String modelName;

    public ReplayChatModel(ResourceLoader resourceLoader,
                           org.springframework.core.env.Environment environment) {
        this.modelName = environment.getProperty("app.model-name", "replay");
        String verdictLocation = environment.getProperty("app.replay.verdict-scenario",
                "classpath:replay/scenarios.json");
        String draftLocation = environment.getProperty("app.replay.drafts-scenario",
                "classpath:replay/scenarios.json");
        this.verdicts = read(resourceLoader, verdictLocation, modelName);
        this.drafts = read(resourceLoader, draftLocation, modelName);
    }

    private static ReplayScenario read(ResourceLoader resourceLoader, String location, String modelName) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            return new ReplayScenario(modelName, Map.of(), Map.of());
        }
        try (InputStream stream = resource.getInputStream()) {
            return JSON.readValue(stream, ReplayScenario.class);
        } catch (IOException e) {
            throw new IllegalStateException("replay scenario could not be read from " + location, e);
        }
    }

    public void reset() {
        attemptCounters.clear();
        draftCounters.clear();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (PromptInspection.isDraft(prompt)) {
            return draftResponse(PromptInspection.deductionId(prompt));
        }
        UUID deductionId = PromptInspection.deductionId(prompt);
        List<Message> messages = prompt.getInstructions();
        long assistantMessages = messages.stream()
                .filter(message -> message.getMessageType() == MessageType.ASSISTANT)
                .count();
        int attemptNo = assistantMessages == 0
                ? attemptCounters.computeIfAbsent(deductionId, id -> new AtomicInteger()).incrementAndGet()
                : attemptCounters.get(deductionId).get();
        ReplayScenario.ReplayDeduction deduction = verdicts.deductions().get(deductionId.toString());
        if (deduction == null) {
            throw new IllegalStateException("no replay scenario for deduction " + deductionId);
        }
        if (attemptNo > deduction.attempts().size()) {
            throw new IllegalStateException("no replay rounds for attempt " + attemptNo);
        }
        ReplayScenario.ReplayAttempt attempt = deduction.attempts().get(attemptNo - 1);
        int roundIndex = (int) assistantMessages;
        if (roundIndex >= attempt.rounds().size()) {
            throw new IllegalStateException("no replay round " + roundIndex + " for attempt " + attemptNo);
        }
        ReplayScenario.ReplayRound round = attempt.rounds().get(roundIndex);
        if (round.delayMs() != null && round.delayMs() > 0) {
            sleep(round.delayMs());
        }
        if (round.error() != null) {
            throw new IllegalStateException("replay round failed: " + round.error());
        }
        AssistantMessage.Builder builder = AssistantMessage.builder();
        if (round.text() != null) {
            builder.content(round.text());
        }
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        if (round.toolCalls() != null) {
            int index = 0;
            for (ReplayScenario.ReplayToolCall call : round.toolCalls()) {
                calls.add(new AssistantMessage.ToolCall("call-" + attemptNo + "-" + roundIndex + "-" + index,
                        "function", call.name(), call.arguments()));
                index++;
            }
        }
        if (!calls.isEmpty()) {
            builder.toolCalls(calls);
        }
        return ChatResponse.builder()
                .generations(List.of(new Generation(builder.build())))
                .metadata("model", modelName)
                .build();
    }

    private ChatResponse draftResponse(UUID deductionId) {
        int index = draftCounters.computeIfAbsent(deductionId, id -> new AtomicInteger()).incrementAndGet();
        List<ReplayScenario.ReplayDraft> entries = drafts.drafts().get(deductionId.toString());
        if (entries == null || index > entries.size()) {
            throw new IllegalStateException(
                    "no replay draft " + index + " for deduction " + deductionId);
        }
        return ChatResponse.builder()
                .generations(List.of(new Generation(
                        AssistantMessage.builder().content(entries.get(index - 1).text()).build())))
                .metadata("model", modelName)
                .build();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("replay round interrupted", e);
        }
    }
}
