package com.deductionaudit.replay;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Profile("replay")
public class ReplayChatModel implements ChatModel {

    private static final Pattern DEDUCTION_ID = Pattern.compile("EDI812:([0-9a-fA-F-]{36})");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ReplayScenario scenario;
    private final Map<UUID, AtomicInteger> attemptCounters = new ConcurrentHashMap<>();
    private final String modelName;

    public ReplayChatModel(ResourceLoader resourceLoader,
                           org.springframework.core.env.Environment environment) {
        this.modelName = environment.getProperty("app.model-name", "replay");
        String location = environment.getProperty("app.replay.scenario", "classpath:replay/scenarios.json");
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            this.scenario = new ReplayScenario(modelName, Map.of());
            return;
        }
        try (InputStream stream = resource.getInputStream()) {
            this.scenario = JSON.readValue(stream, ReplayScenario.class);
        } catch (IOException e) {
            throw new IllegalStateException("replay scenario could not be read from " + location, e);
        }
    }

    public void reset() {
        attemptCounters.clear();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        UUID deductionId = deductionId(prompt);
        List<Message> messages = prompt.getInstructions();
        long assistantMessages = messages.stream()
                .filter(message -> message.getMessageType() == MessageType.ASSISTANT)
                .count();
        int attemptNo = assistantMessages == 0
                ? attemptCounters.computeIfAbsent(deductionId, id -> new AtomicInteger()).incrementAndGet()
                : attemptCounters.get(deductionId).get();
        ReplayScenario.ReplayDeduction deduction = scenario.deductions().get(deductionId.toString());
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

    private static UUID deductionId(Prompt prompt) {
        for (Message message : prompt.getInstructions()) {
            Matcher matcher = DEDUCTION_ID.matcher(message.getText() == null ? "" : message.getText());
            if (matcher.find()) {
                return UUID.fromString(matcher.group(1));
            }
        }
        throw new IllegalStateException("the prompt carries no deduction id");
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
