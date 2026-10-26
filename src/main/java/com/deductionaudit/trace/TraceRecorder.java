package com.deductionaudit.trace;

import java.util.List;
import java.util.UUID;

public interface TraceRecorder {

    void recordModelRound(UUID deductionId, int attemptNo, String model, String request, String response,
                          Integer inputTokens, Integer outputTokens, long durationMs, String error);

    void recordToolCall(UUID deductionId, int attemptNo, String toolName, String arguments, String result,
                        long durationMs, List<String> evidenceIds, String error);

    List<TraceEntry> read(UUID deductionId, int attemptNo);
}
