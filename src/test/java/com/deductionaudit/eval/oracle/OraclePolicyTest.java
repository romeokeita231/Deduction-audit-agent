package com.deductionaudit.eval.oracle;

import com.deductionaudit.agent.PolicyProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OraclePolicyTest {

    @Test
    void policyConstantUsesTheRuntimeShaPath() {
        PolicyProvider provider = new PolicyProvider();
        assertEquals(PolicyProvider.sha256(provider.text()), Oracle.POLICY_SHA256);
        assertEquals(provider.version(), Oracle.POLICY_VERSION);
        assertEquals(12, Oracle.POLICY_VERSION.length());
        assertTrue(Oracle.POLICY_SHA256.startsWith(Oracle.POLICY_VERSION));
    }

    @Test
    void aChangedPolicyByteIsRefused() {
        PolicyProvider provider = new PolicyProvider();
        assertThrows(IllegalStateException.class,
                () -> Oracle.frozen(provider.text() + " ", provider.version()));
        assertThrows(IllegalStateException.class,
                () -> Oracle.frozen(provider.text(), provider.version().substring(0, 11)));
    }

    @Test
    void oracleNeverImportsTheValidators() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/java/com/deductionaudit/eval/oracle"))) {
            List<Path> sources = files.filter(path -> path.toString().endsWith(".java")).toList();
            assertFalse(sources.isEmpty());
            for (Path source : sources) {
                assertFalse(Files.readString(source).contains("agent.validators"),
                        source.getFileName() + " imports the validators");
            }
        }
    }

    @Test
    void policyGapBranchNamesAreStable() {
        assertEquals("PROMOTION_SAME_LEVEL_CLAUSES_DISAGREE",
                Oracle.PROMOTION_SAME_LEVEL_CLAUSES_DISAGREE);
    }
}
