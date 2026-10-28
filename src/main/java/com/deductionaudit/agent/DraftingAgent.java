package com.deductionaudit.agent;

import com.deductionaudit.agent.EvidenceDigestBuilder.DigestItem;
import com.deductionaudit.config.EvaluationProperties;
import com.deductionaudit.domain.Claim;
import com.deductionaudit.shared.ApiException;
import com.deductionaudit.shared.PromptMarker;
import com.deductionaudit.trace.TraceRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DraftingAgent {

    static final String NARRATIVE_CONTRACT = """

            Output contract:
            Answer with one JSON object and nothing else. No prose outside the object.
            The object has exactly one field:
              "narrative": plain text, no markdown, no headings, no bullet lists, no tables and no code fences.
            Narrative rules:
              every evidence id you mention must come from the claim's closed set of references,
              supportingEvidence and basis; mention at least one of those ids; write 3 to 5 paragraphs
              and 120 to 250 words; state the amount once in the opening paragraph,
              and do not restate it in later paragraphs.
            """;

    static final String SKELETON = """

            Follow this four-part skeleton, in paragraph order:
            1. State what the deduction is, the amount at stake, and that the deduction is not supported.
            2. State the basis, naming the evidence ids explicitly.
            3. Make the ask: reverse the deduction and issue a credit memo for the same amount.
            4. Close the letter.
            """;

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    false);
    private static final Pattern EVIDENCE_ID = Pattern.compile(
            "(?<![A-Za-z0-9_])(?:ASN|BOL|POD|PRICE|INVOICE|PROMO|SETTLE|EDI812):[A-Za-z0-9._:\\-·§]+");
    private static final int MAX_FIELD = 2000;

    private final ObjectProvider<ChatModel> chatModel;
    private final TraceRecorder trace;
    private final ExecutorService executor;
    private final EvaluationProperties properties;
    private final PolicyProvider policy;
    private final String modelName;

    public DraftingAgent(ObjectProvider<ChatModel> chatModel, TraceRecorder trace, ExecutorService executor,
                         EvaluationProperties properties, PolicyProvider policy,
                         @Value("${app.model-name:deepseek-chat}") String modelName) {
        this.chatModel = chatModel;
        this.trace = trace;
        this.executor = executor;
        this.properties = properties;
        this.policy = policy;
        this.modelName = modelName;
    }

    public record DraftResult(String narrative, String promptVersion, String model) {
    }

    public DraftResult draft(Claim claim, List<DigestItem> digest) {
        Prompt prompt = new Prompt(List.of(new SystemMessage(systemMessage()),
                new UserMessage(userMessage(claim, digest))), options());
        String request = summarize(prompt);
        List<String> evidenceIds = digest.stream().map(DigestItem::evidenceId).toList();

        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            throw failure(claim, request, evidenceIds, 0, "no chat model is configured");
        }

        Instant started = Instant.now();
        Future<ChatResponse> future = executor.submit(() -> model.call(prompt));
        ChatResponse response;
        try {
            response = future.get(properties.modelRoundTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            long duration = Duration.between(started, Instant.now()).toMillis();
            throw failure(claim, request, evidenceIds, duration, "model round timeout");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            long duration = Duration.between(started, Instant.now()).toMillis();
            throw failure(claim, request, evidenceIds, duration, "model round failed: " + cause.getMessage());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            long duration = Duration.between(started, Instant.now()).toMillis();
            throw failure(claim, request, evidenceIds, duration, "interrupted while waiting for the model");
        }

        long duration = Duration.between(started, Instant.now()).toMillis();
        String text = text(response);
        DraftOutput output;
        try {
            output = parse(text);
        } catch (IllegalArgumentException e) {
            trace.recordDraft(claim.deductionId(), claim.attemptNo(), modelName, request, null, duration,
                    evidenceIds, e.getMessage());
            throw ApiException.serviceUnavailable("DRAFT_FAILED", e.getMessage());
        }

        try {
            validate(claim, output.narrative());
        } catch (ApiException e) {
            trace.recordDraft(claim.deductionId(), claim.attemptNo(), modelName, request, null, duration,
                    evidenceIds, e.getMessage());
            throw e;
        }
        trace.recordDraft(claim.deductionId(), claim.attemptNo(), modelName, request, text, duration,
                evidenceIds, null);
        return new DraftResult(output.narrative(), promptVersion(), modelName);
    }

    private ApiException failure(Claim claim, String request, List<String> evidenceIds, long duration,
                                 String error) {
        trace.recordDraft(claim.deductionId(), claim.attemptNo(), modelName, request, null, duration,
                evidenceIds, error);
        return ApiException.serviceUnavailable("DRAFT_FAILED", error);
    }

    private static DraftOutput parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("the model returned no narrative");
        }
        DraftOutput output;
        try {
            output = JSON.readValue(text.trim(), DraftOutput.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("the model answer is not a draft: " + e.getMessage());
        }
        if (output.narrative() == null || output.narrative().isBlank()) {
            throw new IllegalArgumentException("the model returned no narrative");
        }
        return output;
    }

    private static void validate(Claim claim, String narrative) {
        String text = narrative.trim();
        for (String line : text.split("\n", -1)) {
            String marker = line.trim();
            if (marker.startsWith("#") || marker.startsWith("-") || marker.startsWith("*")
                    || marker.startsWith("|")) {
                throw ApiException.internal("DRAFT_INCONSISTENT",
                        "the narrative contains a markdown line marker");
            }
        }
        if (text.contains("```")) {
            throw ApiException.internal("DRAFT_INCONSISTENT", "the narrative contains a code fence");
        }

        Set<String> allowed = new HashSet<>();
        allowed.addAll(claim.references());
        allowed.addAll(claim.supportingEvidence());
        allowed.addAll(claim.basis());

        List<String> named = new ArrayList<>();
        Matcher matcher = EVIDENCE_ID.matcher(text);
        while (matcher.find()) {
            named.add(matcher.group().replaceAll("[.,;:]+$", ""));
        }
        if (named.isEmpty()) {
            throw ApiException.internal("DRAFT_INCONSISTENT", "the narrative names no evidence id");
        }
        for (String id : named) {
            if (!allowed.contains(id)) {
                throw ApiException.internal("DRAFT_INCONSISTENT",
                        "the narrative names evidence id " + id + " outside the claim closed set");
            }
        }
    }

    private OpenAiChatOptions options() {
        return OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(0.0)
                .responseFormat(OpenAiChatModel.ResponseFormat.builder()
                        .type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT)
                        .build())
                .build();
    }

    private static String userMessage(Claim claim, List<DigestItem> digest) {
        try {
            return "Claim:\n" + JSON.writeValueAsString(claim)
                    + "\n\nEvidence digest:\n" + JSON.writeValueAsString(digest);
        } catch (Exception e) {
            throw ApiException.internal("DRAFT_INCONSISTENT", "the claim or digest cannot be serialized");
        }
    }

    private static String text(ChatResponse response) {
        AssistantMessage output = response == null || response.getResult() == null
                ? null : response.getResult().getOutput();
        return output == null ? null : output.getText();
    }

    private static String summarize(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            sb.append(message.getMessageType()).append(": ").append(truncate(message.getText())).append('\n');
        }
        return sb.toString();
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_FIELD ? value : value.substring(0, MAX_FIELD) + "...";
    }

    String systemMessage() {
        return NARRATIVE_CONTRACT + SKELETON + policy.text() + PromptMarker.DRAFT;
    }

    public String promptVersion() {
        return PolicyProvider.sha256(systemMessage()).substring(0, 12);
    }
}
