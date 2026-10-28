package com.deductionaudit.agent;

import com.deductionaudit.trace.TraceEntry;
import com.deductionaudit.trace.TraceRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class InstrumentedToolCallbackTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final RecordingTrace trace = new RecordingTrace();

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private static ToolCallback callback(String name, Duration duration) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description(name)
                .inputSchema("{\"type\":\"object\"}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                if (!duration.isZero()) {
                    try {
                        Thread.sleep(duration.toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                return "{\"found\":true,\"evidenceIds\":[\"BOL:SHP-1\"]}";
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return call(toolInput);
            }
        };
    }

    private static EvaluationRun run() {
        return new EvaluationRun(UUID.randomUUID(), 1, Instant.now(), Instant.now().plusSeconds(45));
    }

    @Test
    void stopsAtTheTotalToolCallLimitWithoutFailingTheRun() {
        EvaluationRun run = run();
        InstrumentedToolCallback callback = new InstrumentedToolCallback(callback("getBol", Duration.ZERO),
                run, trace, executor, 2, Duration.ofSeconds(3));
        assertThat(callback.call("{}", null)).contains("BOL:SHP-1");
        assertThat(callback.call("{}", null)).contains("BOL:SHP-1");
        assertThat(callback.call("{}", null)).contains("TOOL_LIMIT_REACHED");
        assertThat(run.toolCallCount()).isEqualTo(2);
        assertThat(run.infrastructureFailure()).isNull();
        assertThat(trace.tools).hasSize(2);
    }

    @Test
    void aSlowToolIsAnInfrastructureFailureWithAPartialTrace() {
        EvaluationRun run = run();
        InstrumentedToolCallback callback = new InstrumentedToolCallback(callback("getPod", Duration.ofMillis(500)),
                run, trace, executor, 6, Duration.ofMillis(100));
        assertThat(callback.call("{}", null)).contains("TOOL_TIMEOUT");
        assertThat(run.infrastructureFailure()).contains("tool timeout: getPod");
        assertThat(trace.tools).singleElement().satisfies(entry -> {
            assertThat(entry.name()).isEqualTo("getPod");
            assertThat(entry.error()).isEqualTo("tool timeout");
            assertThat(entry.durationMs()).isGreaterThanOrEqualTo(100);
        });
    }

    private static final class RecordingTrace implements TraceRecorder {

        private final List<TraceEntry> tools = new ArrayList<>();

        @Override
        public void recordModelRound(UUID deductionId, int attemptNo, String model, String request,
                                     String response, Integer inputTokens, Integer outputTokens,
                                     long durationMs, String error) {
        }

        @Override
        public void recordToolCall(UUID deductionId, int attemptNo, String toolName, String arguments,
                                   String result, long durationMs, List<String> evidenceIds, String error) {
            tools.add(new TraceEntry(deductionId, attemptNo, "tool", toolName, Instant.now(), durationMs,
                    arguments, result, evidenceIds, null, null, error));
        }

        @Override
        public List<TraceEntry> read(UUID deductionId, int attemptNo) {
            return List.copyOf(tools);
        }
    }
}
