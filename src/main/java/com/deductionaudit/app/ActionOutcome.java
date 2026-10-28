package com.deductionaudit.app;

import com.deductionaudit.domain.DeductionStatus;

import java.time.Instant;
import java.util.UUID;

public record ActionOutcome(
        UUID deductionId,
        DeductionStatus status,
        String disputeId,
        Instant openedAt,
        Instant closedAt,
        String result) {
}
