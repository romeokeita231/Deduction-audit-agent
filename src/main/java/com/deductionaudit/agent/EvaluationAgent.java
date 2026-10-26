package com.deductionaudit.agent;

import com.deductionaudit.agent.validators.DeductionValidator;
import com.deductionaudit.config.EvaluationProperties;
import com.deductionaudit.domain.AgentVerdict;
import com.deductionaudit.domain.ClosedSet;
import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.EvidenceIds;
import com.deductionaudit.tools.EvidenceTools;
import com.deductionaudit.trace.TraceRecorder;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class EvaluationAgent {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            false);

    private final ObjectProvider<ChatModel> chatModel;
    private final TraceRecorder trace;
    private final ExecutorService executor;
    private final EvaluationProperties properties;
    private final PolicyProvider policy;
    private final EvidenceTools tools;
    private final ToolCallingManager toolCallingManager;
    private final String modelName;

    public EvaluationAgent(ObjectProvider<ChatModel> chatModel, TraceRecorder trace, ExecutorService executor,
                           EvaluationProperties properties, PolicyProvider policy, EvidenceTools tools,
                           @Value("${app.model-name:deepseek-chat}") String modelName) {
        this.chatModel = chatModel;
        this.trace = trace;
        this.executor = executor;
        this.properties = properties;
        this.policy = policy;
        this.tools = tools;
        this.modelName = modelName;
        this.toolCallingManager = DefaultToolCallingManager.builder()
                .maxTotalToolCalls(properties.maxToolCalls())
                .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
                .resolutionFallbackEnabled(false)
                .build();
    }

    public AgentVerdict evaluate(Deduction deduction, DeductionValidator validator, EvaluationRun run) {
        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            throw new InfrastructureFailure("no chat model is configured");
        }
        ToolCallback[] callbacks = callbacks(validator.allowedTools(), run);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .temperature(0.0)
                .toolCallbacks(Arrays.asList(callbacks))
                .build();
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(validator.systemPrompt(policy.text())));
        messages.add(new UserMessage(validator.userPrompt(deduction)));
        Prompt prompt = new Prompt(messages, options);

        ChatResponse response = callRound(model, prompt, run);
        int rounds = 1;
        while (response.hasToolCalls() && rounds <= properties.maxToolCalls()) {
            ToolExecutionResult execution;
            try {
                execution = toolCallingManager.executeToolCalls(prompt, response);
            } catch (RuntimeException e) {
                throw new InfrastructureFailure("tool execution failed: " + e.getMessage());
            }
            if (execution == null || execution.conversationHistory().isEmpty()) {
                break;
            }
            if (execution.returnDirect()) {
                break;
            }
            prompt = new Prompt(execution.conversationHistory(), options);
            response = callRound(model, prompt, run);
            rounds++;
        }
        if (run.infrastructureFailure() != null) {
            throw new InfrastructureFailure(run.infrastructureFailure());
        }
        AgentVerdict verdict = parse(response);
        if (verdict == null || verdict.verdict() == null) {
            throw new InfrastructureFailure("the model returned no verdict");
        }
        List<String> references = verdict.references() == null ? List.of() : verdict.references();
        List<String> supporting = verdict.supportingEvidence() == null ? List.of()
                : verdict.supportingEvidence();
        try {
            ClosedSet.assertClosedSet(references, supporting, run.returnedEvidenceIds(),
                    EvidenceIds.edi812(deduction.id()));
        } catch (ClosedSet.ClosedSetViolation e) {
            throw new EvidenceClosedSetViolation(e.getMessage(), e);
        }
        return new AgentVerdict(verdict.verdict(),
                verdict.missingEvidence() == null ? List.of() : verdict.missingEvidence(),
                references, supporting, verdict.reasoning() == null ? "" : verdict.reasoning());
    }

    private ChatResponse callRound(ChatModel model, Prompt prompt, EvaluationRun run) {
        Instant started = Instant.now();
        Future<ChatResponse> future = executor.submit(() -> model.call(prompt));
        try {
            ChatResponse response = future.get(properties.modelRoundTimeout().toMillis(),
                    TimeUnit.MILLISECONDS);
            long duration = Duration.between(started, Instant.now()).toMillis();
            recordModel(run, prompt, response, duration, null);
            return response;
        } catch (TimeoutException e) {
            future.cancel(true);
            long duration = Duration.between(started, Instant.now()).toMillis();
            run.failInfrastructure("model round timeout");
            trace.recordModelRound(run.deductionId(), run.attemptNo(), modelName, summarize(prompt),
                    null, null, null, duration, "model round timeout");
            throw new InfrastructureFailure("model round timeout");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            long duration = Duration.between(started, Instant.now()).toMillis();
            run.failInfrastructure("model round failed: " + cause.getMessage());
            trace.recordModelRound(run.deductionId(), run.attemptNo(), modelName, summarize(prompt),
                    null, null, null, duration, cause.getMessage());
            throw new InfrastructureFailure("model round failed: " + cause.getMessage());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new InfrastructureFailure("interrupted while waiting for the model");
        }
    }

    private void recordModel(EvaluationRun run, Prompt prompt, ChatResponse response, long duration,
                             String error) {
        String model = response.getMetadata() == null || response.getMetadata().getModel() == null
                ? modelName : response.getMetadata().getModel();
        Integer inputTokens = response.getMetadata() == null || response.getMetadata().getUsage() == null
                ? null : response.getMetadata().getUsage().getPromptTokens();
        Integer outputTokens = response.getMetadata() == null || response.getMetadata().getUsage() == null
                ? null : response.getMetadata().getUsage().getCompletionTokens();
        trace.recordModelRound(run.deductionId(), run.attemptNo(), model, summarize(prompt),
                summarize(response), inputTokens, outputTokens, duration, error);
    }

    private static AgentVerdict parse(ChatResponse response) {
        AssistantMessage output = response == null || response.getResult() == null
                ? null : response.getResult().getOutput();
        if (output == null || output.getText() == null || output.getText().isBlank()) {
            return null;
        }
        String text = stripFences(output.getText());
        try {
            return JSON.readValue(text, AgentVerdict.class);
        } catch (Exception e) {
            throw new InfrastructureFailure("the model answer is not a verdict: " + e.getMessage());
        }
    }

    private static String stripFences(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline < 0) {
            return trimmed;
        }
        String withoutHeader = trimmed.substring(firstNewline + 1);
        int fence = withoutHeader.lastIndexOf("```");
        return fence >= 0 ? withoutHeader.substring(0, fence).trim() : withoutHeader.trim();
    }

    private static String summarize(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            sb.append(message.getMessageType()).append(": ").append(truncate(text(message))).append('\n');
        }
        return sb.toString();
    }

    private static String summarize(ChatResponse response) {
        AssistantMessage output = response == null || response.getResult() == null
                ? null : response.getResult().getOutput();
        if (output == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(truncate(output.getText()));
        for (AssistantMessage.ToolCall call : output.getToolCalls()) {
            sb.append("\ntoolCall: ").append(call.name()).append(' ').append(call.arguments());
        }
        return sb.toString();
    }

    private static String text(Message message) {
        if (message instanceof AssistantMessage assistant) {
            StringBuilder sb = new StringBuilder(assistant.getText() == null ? "" : assistant.getText());
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                sb.append("\ntoolCall: ").append(call.name()).append(' ').append(call.arguments());
            }
            return sb.toString();
        }
        return message.getText();
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 2000 ? value : value.substring(0, 2000) + "...";
    }

    public String promptVersion(DeductionValidator validator) {
        return PolicyProvider.sha256(validator.systemPrompt(policy.text())).substring(0, 12);
    }

    public String policyVersion() {
        return policy.version();
    }

    public String modelName() {
        return modelName;
    }

    private ToolCallback[] callbacks(List<String> allowedTools, EvaluationRun run) {
        Set<String> allowed = Set.copyOf(allowedTools);
        return Arrays.stream(ToolCallbacks.from(tools))
                .filter(callback -> allowed.contains(callback.getToolDefinition().name()))
                .map(callback -> (ToolCallback) new InstrumentedToolCallback(callback, run, trace, executor,
                        properties.maxToolCalls(), properties.toolTimeout()))
                .toArray(ToolCallback[]::new);
    }

    public static final class InfrastructureFailure extends RuntimeException {
        public InfrastructureFailure(String message) {
            super(message);
        }
    }

    public static final class EvidenceClosedSetViolation extends RuntimeException {
        public EvidenceClosedSetViolation(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
