package com.deductionaudit.agent;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class EvaluationRun {

    private final UUID deductionId;
    private final int attemptNo;
    private final Instant startedAt;
    private final Instant deadline;
    private final Set<String> returnedEvidenceIds = ConcurrentHashMap.newKeySet();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private volatile String infrastructureFailure;

    public EvaluationRun(UUID deductionId, int attemptNo, Instant startedAt, Instant deadline) {
        this.deductionId = deductionId;
        this.attemptNo = attemptNo;
        this.startedAt = startedAt;
        this.deadline = deadline;
    }

    public UUID deductionId() {
        return deductionId;
    }

    public int attemptNo() {
        return attemptNo;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant deadline() {
        return deadline;
    }

    public Set<String> returnedEvidenceIds() {
        return returnedEvidenceIds;
    }

    public void evidenceReturned(List<String> ids) {
        if (ids != null) {
            returnedEvidenceIds.addAll(ids);
        }
    }

    public int toolCallCount() {
        return toolCalls.get();
    }

    public int incrementToolCalls() {
        return toolCalls.incrementAndGet();
    }

    public void failInfrastructure(String reason) {
        if (infrastructureFailure == null) {
            infrastructureFailure = reason;
        }
    }

    public String infrastructureFailure() {
        return infrastructureFailure;
    }
}
