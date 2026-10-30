package com.deductionaudit.eval;

import com.deductionaudit.domain.DeductionCategory;
import com.deductionaudit.domain.VerdictValue;
import com.deductionaudit.eval.corpus.CorpusCase;
import com.deductionaudit.eval.corpus.CorpusCase.Situation;
import com.deductionaudit.eval.corpus.CorpusEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorpusGeneratorTest {

    @Test
    void theSameSeedWritesByteIdenticalJson(@TempDir Path first, @TempDir Path second) throws IOException {
        CorpusGenerator.write(first);
        CorpusGenerator.write(second);
        Map<String, byte[]> a = bytes(first);
        Map<String, byte[]> b = bytes(second);
        assertEquals(a.keySet(), b.keySet());
        for (String name : a.keySet()) {
            assertArrayEquals(a.get(name), b.get(name), name + " is not byte identical");
        }
    }

    @Test
    void everyCellHoldsSevenCasesAndEveryStateBucketIsFilled() {
        List<CorpusEntry> entries = CorpusGenerator.generate();
        assertEquals(DeductionCategory.values().length * Situation.values().length
                * CorpusGenerator.CASES_PER_CELL, entries.size());
        Map<String, Integer> states = new HashMap<>();
        Map<String, Integer> arbitratedDisputable = new HashMap<>();
        for (CorpusEntry entry : entries) {
            CorpusCase facts = entry.facts();
            states.merge(entry.stateBucket(), 1, Integer::sum);
            if (facts.cell().situation() == Situation.CONFLICT_ARBITRATED
                    && entry.expectation().expectedState() == VerdictValue.DISPUTABLE) {
                arbitratedDisputable.merge(facts.cell().category().name(), 1, Integer::sum);
            }
        }
        for (String bucket : List.of("DEDUCTION_STANDS", "DISPUTABLE", "EVIDENCE_GAP", "POLICY_GAP")) {
            assertTrue(states.getOrDefault(bucket, 0) > 0, "empty state bucket " + bucket);
        }
        for (DeductionCategory category : DeductionCategory.values()) {
            assertTrue(arbitratedDisputable.getOrDefault(category.name(), 0) >= 2,
                    "arbitrated " + category + " has fewer than two disputable cases");
        }
    }

    @Test
    void theGeneratorNeverShipsInTheJar() {
        assertTrue(Files.exists(Path.of("src/test/java/com/deductionaudit/eval/CorpusGenerator.java")));
        assertFalse(Files.exists(Path.of("src/main/java/com/deductionaudit/eval/CorpusGenerator.java")));
    }

    private static Map<String, byte[]> bytes(Path directory) throws IOException {
        Map<String, byte[]> result = new HashMap<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                result.put(file.getFileName().toString(), Files.readAllBytes(file));
            }
        }
        return result;
    }
}
