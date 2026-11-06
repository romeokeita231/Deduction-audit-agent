package com.deductionaudit.eval.oracle;

import com.deductionaudit.domain.ParkedReason;
import com.deductionaudit.domain.VerdictValue;

import java.util.List;

public record OracleExpectation(
        VerdictValue expectedState,
        ParkedReason expectedParkedReason,
        List<String> expectedSupportingEvidence,
        String oracleBranch) {

    public OracleExpectation {
        expectedSupportingEvidence = expectedSupportingEvidence == null
                ? List.of() : List.copyOf(expectedSupportingEvidence);
    }
}
