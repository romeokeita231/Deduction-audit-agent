package com.deductionaudit.web;

import com.deductionaudit.app.ActionOutcome;
import com.deductionaudit.app.DocumentView;
import com.deductionaudit.domain.DeductionStatus;

import java.time.Instant;
import java.util.UUID;

public record DisputeResponse(
        UUID deductionId,
        DeductionStatus status,
        String disputeId,
        Instant openedAt,
        Instant closedAt,
        String result,
        DocumentView document) {

    public static DisputeResponse from(ActionOutcome outcome, DocumentView document) {
        return new DisputeResponse(outcome.deductionId(), outcome.status(), outcome.disputeId(),
                outcome.openedAt(), outcome.closedAt(), outcome.result(), document);
    }
}
