package com.deductionaudit.domain;

import java.time.Duration;
import java.util.List;

public record TransitionContext(
        boolean windowExpired,
        VerdictValue verdict,
        List<MissingEvidence> missingEvidence,
        int priorAttempts,
        Duration remainingBudget,
        Duration retryMinRemaining,
        boolean hasDisputeRecord,
        String requestedResult) {

    public static TransitionContext empty() {
        return new TransitionContext(false, null, List.of(), 0, Duration.ZERO, Duration.ZERO, false, null);
    }

    public static TransitionContext atWindow(boolean expired) {
        return new TransitionContext(expired, null, List.of(), 0, Duration.ZERO, Duration.ZERO, false, null);
    }

    public static TransitionContext forVerdict(VerdictValue verdict, List<MissingEvidence> missingEvidence) {
        return new TransitionContext(false, verdict, missingEvidence == null ? List.of() : missingEvidence,
                0, Duration.ZERO, Duration.ZERO, false, null);
    }

    public static TransitionContext forRetry(int priorAttempts, Duration remainingBudget,
                                             Duration retryMinRemaining) {
        return new TransitionContext(false, null, List.of(), priorAttempts, remainingBudget,
                retryMinRemaining, false, null);
    }

    public static TransitionContext forResult(String result) {
        return new TransitionContext(false, null, List.of(), 0, Duration.ZERO, Duration.ZERO, true, result);
    }
}
