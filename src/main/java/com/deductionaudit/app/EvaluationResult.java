package com.deductionaudit.app;

import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.DeductionStatus;
import com.deductionaudit.domain.ParkedReason;
import com.deductionaudit.domain.VerdictRecord;

import java.util.UUID;

public record EvaluationResult(
        UUID deductionId,
        DeductionStatus status,
        ParkedReason parkedReason,
        Integer attemptNo,
        VerdictRecord verdict) {
}
