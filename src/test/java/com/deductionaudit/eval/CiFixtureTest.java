package com.deductionaudit.eval;

import com.deductionaudit.IntegrationTestBase;
import com.deductionaudit.domain.VerdictValue;
import com.deductionaudit.eval.corpus.CorpusLoader;
import com.deductionaudit.eval.corpus.CorpusLoader.LoadedCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = {
        "app.replay.verdict-scenario=classpath:eval/cassette/verdict.json",
        "app.replay.drafts-scenario=classpath:eval/cassette/drafts.json"
})
class CiFixtureTest extends IntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final Path BASELINE = ROOT.resolve("src/test/resources/eval/baseline");

    @Autowired
    private CorpusLoader corpus;

    @Autowired
    private EvalCaseDriver driver;

    @Test
    void theManifestRunsThroughReplayAndMatchesTheCommittedBaseline(@TempDir Path work)
            throws IOException, InterruptedException {
        List<String> caseIds = manifest();
        List<LoadedCase> cases = select(caseIds);
        assertCassetteCoverage(cases);

        List<NdjsonExporter.EvalRow> rows = driver.runAll(cases, EvalCaseDriver.MAX_CONCURRENCY);
        assertEquals(caseIds.size(), rows.size());
        for (int index = 0; index < caseIds.size(); index++) {
            assertEquals(caseIds.get(index), rows.get(index).caseId());
        }

        Path results = work.resolve("results.ndjson");
        NdjsonExporter.write(results, rows, true);
        Path metrics = work.resolve("metrics.json");
        runMetrics(results, metrics);

        if (Boolean.getBoolean("eval.update-baseline")) {
            writeBaseline(results, metrics);
        }

        List<String> expected = Files.readAllLines(BASELINE.resolve("results.ndjson"), StandardCharsets.UTF_8);
        List<String> actual = Files.readAllLines(results, StandardCharsets.UTF_8);
        assertEquals(expected.size(), actual.size(), "the baseline and the run differ in row count");
        for (int index = 0; index < expected.size(); index++) {
            JsonNode expectedRow = JSON.readTree(expected.get(index));
            JsonNode actualRow = JSON.readTree(actual.get(index));
            assertEquals(expectedRow, actualRow,
                    "the row diverged from the baseline for " + expectedRow.get("caseId").asText());
        }

        JsonNode expectedMetrics = JSON.readTree(BASELINE.resolve("metrics.json").toFile());
        JsonNode actualMetrics = JSON.readTree(metrics.toFile());
        assertEquals(stripProvenance(expectedMetrics), stripProvenance(actualMetrics),
                "metrics.json diverged from the committed baseline");

        assertConfusionCells(actualMetrics);
    }

    private List<String> manifest() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/eval/ci-manifest.json")) {
            JsonNode root = JSON.readTree(stream);
            List<String> caseIds = new ArrayList<>();
            root.get("caseIds").forEach(node -> caseIds.add(node.asText()));
            assertEquals(15, caseIds.size(), "the manifest must cover three categories and five situations");
            return caseIds;
        }
    }

    private List<LoadedCase> select(List<String> caseIds) {
        Map<String, LoadedCase> available = new TreeMap<>();
        for (LoadedCase loaded : corpus.load(java.time.Clock.fixed(FIXED_NOW, java.time.ZoneOffset.UTC))) {
            available.put(loaded.facts().caseId(), loaded);
        }
        List<LoadedCase> selected = new ArrayList<>();
        for (String caseId : caseIds) {
            LoadedCase loaded = available.get(caseId);
            assertNotNull(loaded, "the manifest names a case the corpus does not hold: " + caseId);
            selected.add(loaded);
        }
        return selected;
    }

    private void assertCassetteCoverage(List<LoadedCase> cases) throws IOException {
        JsonNode verdicts = cassette("verdict").get("deductions");
        JsonNode drafts = cassette("drafts").get("drafts");
        for (LoadedCase loaded : cases) {
            String deductionId = CorpusLoader.deductionId(loaded.facts().caseId()).toString();
            assertTrue(verdicts.has(deductionId),
                    "the verdict cassette has no entry for " + loaded.facts().caseId());
            boolean disputable = loaded.facts().expectedState() == VerdictValue.DISPUTABLE;
            if (disputable) {
                assertTrue(drafts.has(deductionId),
                        "the drafts cassette has no entry for " + loaded.facts().caseId());
            }
        }
    }

    private JsonNode cassette(String channel) throws IOException {
        Path path = ROOT.resolve("src/test/resources/eval/cassette/" + channel + ".json");
        return JSON.readTree(path.toFile());
    }

    private void runMetrics(Path results, Path metrics) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("python", "-m", "eval.metrics", results.toString(),
                "--output", metrics.toString())
                .directory(ROOT.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), "the metrics package failed:\n" + output);
        assertTrue(Files.exists(metrics), "the metrics package wrote no output");
    }

    private void writeBaseline(Path results, Path metrics) throws IOException {
        Files.createDirectories(BASELINE);
        Files.copy(results, BASELINE.resolve("results.ndjson"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(metrics, BASELINE.resolve("metrics.json"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static JsonNode stripProvenance(JsonNode metrics) {
        com.fasterxml.jackson.databind.node.ObjectNode copy = metrics.deepCopy();
        copy.remove("commit");
        return copy;
    }

    private static void assertConfusionCells(JsonNode metrics) {
        JsonNode confusion = metrics.get("metric1").get("confusion");
        Map<String, Integer> cells = new TreeMap<>();
        for (JsonNode entry : confusion) {
            String cell = entry.get("category").asText() + "/" + entry.get("situation").asText();
            cells.merge(cell, entry.get("n").asInt(), Integer::sum);
        }
        assertEquals(15, cells.size(), "the manifest must cover fifteen cells");
        for (Map.Entry<String, Integer> cell : cells.entrySet()) {
            assertEquals(1, cell.getValue(), "the cell " + cell.getKey() + " holds more than one case");
        }
    }
}
