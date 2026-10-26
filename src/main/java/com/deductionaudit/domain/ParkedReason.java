package com.deductionaudit.domain;

import java.util.List;

public enum ParkedReason {
    UNMAPPED_CODE,
    EVAL_FAILED,
    EVIDENCE_GAP,
    POLICY_GAP;

    public static ParkedReason forInconclusive(VerdictValue verdict, List<MissingEvidence> missingEvidence) {
        if (verdict != VerdictValue.INCONCLUSIVE) {
            return null;
        }
        return missingEvidence == null || missingEvidence.isEmpty() ? POLICY_GAP : EVIDENCE_GAP;
    }
}
