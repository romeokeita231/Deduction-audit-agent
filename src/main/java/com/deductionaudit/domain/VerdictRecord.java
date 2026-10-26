package com.deductionaudit.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record VerdictRecord(
        UUID deductionId,
        int attemptNo,
        VerdictValue verdict,
        List<MissingEvidence> missingEvidence,
        List<String> references,
        List<String> supportingEvidence,
        String reasoning,
        String promptVersion,
        String policyVersion,
        int mappingVersion,
        String model,
        Instant evaluatedAt) {
}
