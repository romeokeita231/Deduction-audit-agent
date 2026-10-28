package com.deductionaudit.domain;

import java.util.List;

public record AgentVerdict(
        VerdictValue verdict,
        List<MissingEvidence> missingEvidence,
        List<String> references,
        List<String> supportingEvidence,
        String reasoning) {
}
