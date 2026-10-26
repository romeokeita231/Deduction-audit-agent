package com.deductionaudit.agent;

import com.deductionaudit.config.EvaluationProperties;
import com.deductionaudit.trace.TraceRecorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class InstrumentedToolCallback implements ToolCallback {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolCallback delegate;
    private final EvaluationRun run;
    private final TraceRecorder trace;
    private final ExecutorService executor;
    private final int maxToolCalls;
    private final Duration toolTimeout;

    public InstrumentedToolCallback(ToolCallback delegate, EvaluationRun run, TraceRecorder trace,
                                    ExecutorService executor, int maxToolCalls, Duration toolTimeout) {
        this.delegate = delegate;
        this.run = run;
        this.trace = trace;
        this.executor = executor;
        this.maxToolCalls = maxToolCalls;
        this.toolTimeout = toolTimeout;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = delegate.getToolDefinition().name();
        if (run.toolCallCount() >= maxToolCalls) {
            return errorPayload("TOOL_LIMIT_REACHED");
        }
        run.incrementToolCalls();
        Instant started = Instant.now();
        Future<String> future = executor.submit(() -> delegate.call(toolInput, toolContext));
        try {
            String result = future.get(toolTimeout.toMillis(), TimeUnit.MILLISECONDS);
            long duration = Duration.between(started, Instant.now()).toMillis();
            List<String> evidenceIds = evidenceIds(result);
            run.evidenceReturned(evidenceIds);
            trace.recordToolCall(run.deductionId(), run.attemptNo(), name, toolInput, result, duration,
                    evidenceIds, null);
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            long duration = Duration.between(started, Instant.now()).toMillis();
            run.failInfrastructure("tool timeout: " + name);
            trace.recordToolCall(run.deductionId(), run.attemptNo(), name, toolInput, null, duration,
                    List.of(), "tool timeout");
            return errorPayload("TOOL_TIMEOUT");
        } catch (ExecutionException e) {
            long duration = Duration.between(started, Instant.now()).toMillis();
            String message = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
            run.failInfrastructure("tool failed: " + name);
            trace.recordToolCall(run.deductionId(), run.attemptNo(), name, toolInput, null, duration,
                    List.of(), message);
            return errorPayload("TOOL_FAILED");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling tool " + name, e);
        }
    }

    private static List<String> evidenceIds(String result) {
        if (result == null || result.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = JSON.readTree(result).path("evidenceIds");
            List<String> ids = new ArrayList<>();
            node.forEach(value -> ids.add(value.asText()));
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String errorPayload(String code) {
        return "{\"error\":\"" + code + "\",\"evidenceIds\":[]}";
    }
}
