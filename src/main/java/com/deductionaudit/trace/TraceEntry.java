package com.deductionaudit.trace;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TraceEntry(
        UUID deductionId,
        int attemptNo,
        String type,
        String name,
        Instant at,
        long durationMs,
        String request,
        String response,
        List<String> evidenceIds,
        Integer inputTokens,
        Integer outputTokens,
        String error) {
}
