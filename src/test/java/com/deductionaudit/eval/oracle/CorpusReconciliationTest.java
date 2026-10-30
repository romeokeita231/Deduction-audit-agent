package com.deductionaudit.eval.oracle;

import com.deductionaudit.domain.ParkedReason;
import com.deductionaudit.domain.VerdictValue;
import com.deductionaudit.eval.corpus.CorpusLoader;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorpusReconciliationTest {

    private static final Clock FIXED = Clock.fixed(
            Instant.parse("2026-03-10T06:59:00Z"), ZoneOffset.UTC);

    @Test
    void everyCaseMatchesItsAnnotation() {
        List<CorpusLoader.LoadedCase> cases = new CorpusLoader(new DefaultResourceLoader()).load(FIXED);
        assertFalse(cases.isEmpty());
        Oracle oracle = new Oracle();
        for (CorpusLoader.LoadedCase loaded : cases) {
            OracleExpectation expected = oracle.expectation(loaded.facts());
            assertEquals(loaded.expectation(), expected, loaded.facts().caseId());
        }
    }

    @Test
    void policyGapOnlyComesFromTheEnumeratedBranches() {
        List<CorpusLoader.LoadedCase> cases = new CorpusLoader(new DefaultResourceLoader()).load(FIXED);
        Oracle oracle = new Oracle();
        for (CorpusLoader.LoadedCase loaded : cases) {
            String caseId = loaded.facts().caseId();
            OracleExpectation expected = oracle.expectation(loaded.facts());
            if (expected.expectedState() == VerdictValue.INCONCLUSIVE) {
                assertTrue(expected.expectedSupportingEvidence().isEmpty(), caseId);
                if (expected.expectedParkedReason() == ParkedReason.POLICY_GAP) {
                    assertTrue(Oracle.isPolicyGapBranch(expected.oracleBranch()), caseId);
                }
            } else {
                assertFalse(expected.expectedSupportingEvidence().isEmpty(), caseId);
            }
        }
    }
}
