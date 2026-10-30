package com.deductionaudit.eval.corpus;

import com.deductionaudit.domain.VerdictValue;
import com.deductionaudit.eval.oracle.OracleExpectation;

public record CorpusEntry(CorpusCase facts, OracleExpectation expectation) {

    public String stateBucket() {
        return expectation.expectedState() == VerdictValue.INCONCLUSIVE
                ? expectation.expectedParkedReason().name()
                : expectation.expectedState().name();
    }
}
