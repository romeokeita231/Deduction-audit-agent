package com.deductionaudit.eval;

import com.deductionaudit.eval.corpus.CorpusLoader;
import com.deductionaudit.eval.corpus.CorpusLoader.LoadedCase;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;

class ReferenceDataMigrationTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V3__reference_data.sql");
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-03-10T06:59:00Z"), ZoneOffset.UTC);

    @Test
    void everyRetailerAndReasonCodeInTheCorpusIsDeclaredInTheMigration() throws IOException {
        String migration = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        Set<String> retailers = values(migration, "INSERT INTO retailer",
                Pattern.compile("\\(\\s*'([^']*)'"));
        Set<String> mappings = values(migration, "INSERT INTO reason_code_mapping",
                Pattern.compile("\\(\\s*'([^']*)'\\s*,\\s*'([^']*)'"), true);

        List<LoadedCase> cases = new CorpusLoader(new DefaultResourceLoader()).load(FIXED);
        Set<String> problems = new HashSet<>();
        for (LoadedCase loaded : cases) {
            String caseId = loaded.facts().caseId();
            String retailerId = loaded.facts().retailerId();
            String reasonCode = loaded.facts().reasonCode();
            if (!retailers.contains(retailerId)) {
                problems.add(caseId + " references retailer " + retailerId + " that V3 does not declare");
            }
            if (!mappings.contains(retailerId + "|" + reasonCode)) {
                problems.add(caseId + " references reason code " + retailerId + "/" + reasonCode
                        + " that V3 does not map");
            }
        }
        if (!problems.isEmpty()) {
            fail(String.join("\n", problems));
        }
    }

    private static Set<String> values(String sql, String insert, Pattern row) {
        return values(sql, insert, row, false);
    }

    private static Set<String> values(String sql, String insert, Pattern row, boolean compound) {
        int start = sql.indexOf(insert);
        int end = sql.indexOf(';', start);
        String block = sql.substring(start, end);
        Matcher matcher = row.matcher(block);
        Set<String> found = new HashSet<>();
        while (matcher.find()) {
            found.add(compound ? matcher.group(1) + "|" + matcher.group(2) : matcher.group(1));
        }
        return found;
    }
}
