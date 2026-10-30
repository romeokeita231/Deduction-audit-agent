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
import static org.junit.jupiter.api.Assertions.assertNull;
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
        long started = System.nanoTime();
        List<ManifestEntry> entries = manifest();
        List<LoadedCase> cases = select(entries);
        assertCassetteCoverage(cases, entries);

        List<NdjsonExporter.EvalRow> rows = driver.runAll(cases, EvalCaseDriver.MAX_CONCURRENCY);
        assertEquals(entries.size(), rows.size());
        for (int index = 0; index < entries.size(); index++) {
            assertEquals(entries.get(index).caseId(), rows.get(index).caseId());
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

        long elapsedNanos = System.nanoTime() - started;
        long elapsedMillis = elapsedNanos / 1_000_000;
        ((com.fasterxml.jackson.databind.node.ObjectNode) actualMetrics)
                .putObject("fixture").put("elapsedMs", elapsedMillis);
        JSON.writeValue(metrics.toFile(), actualMetrics);
        System.out.println("fixture elapsed " + elapsedMillis + " ms");
        assertTrue(elapsedNanos < 120_000_000_000L,
                "the fixture took " + elapsedMillis + " ms, above the two-minute budget");
    }

    @Test
    void theBaselineMetricsMirrorTheManifestsNegativeControls() throws IOException {
        JsonNode metrics = JSON.readTree(BASELINE.resolve("metrics.json").toFile());
        JsonNode controls = metrics.get("negativeControls");
        assertNotNull(controls, "metrics.json must carry negativeControls, even when it is empty");
        List<String> fromMetrics = new ArrayList<>();
        for (JsonNode node : controls) {
            assertTrue(node.hasNonNull("why"),
                    "the negative control " + node.get("caseId").asText() + " must carry its why");
            fromMetrics.add(node.get("caseId").asText() + " | " + node.get("why").asText());
        }
        List<String> fromManifest = new ArrayList<>();
        for (ManifestEntry entry : manifest()) {
            if (entry.why() != null) {
                fromManifest.add(entry.caseId() + " | " + entry.why());
            }
        }
        assertEquals(fromManifest, fromMetrics,
                "the manifest and metrics.json disagree on which cases are negative controls");
    }

    private record ManifestEntry(String caseId, String why) {
    }

    private List<ManifestEntry> manifest() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/eval/ci-manifest.json")) {
            JsonNode root = JSON.readTree(stream);
            List<ManifestEntry> entries = new ArrayList<>();
            for (JsonNode node : root.get("cases")) {
                JsonNode why = node.get("why");
                entries.add(new ManifestEntry(node.get("caseId").asText(),
                        why == null || why.isNull() ? null : why.asText()));
            }
            assertEquals(15, entries.size(), "the manifest must cover three categories and five situations");
            return entries;
        }
    }

    private List<LoadedCase> select(List<ManifestEntry> entries) {
        Map<String, LoadedCase> available = new TreeMap<>();
        for (LoadedCase loaded : corpus.load(java.time.Clock.fixed(FIXED_NOW, java.time.ZoneOffset.UTC))) {
            available.put(loaded.facts().caseId(), loaded);
        }
        List<LoadedCase> selected = new ArrayList<>();
        for (ManifestEntry entry : entries) {
            LoadedCase loaded = available.get(entry.caseId());
            assertNotNull(loaded, "the manifest names a case the corpus does not hold: " + entry.caseId());
            selected.add(loaded);
        }
        return selected;
    }

    private void assertCassetteCoverage(List<LoadedCase> cases, List<ManifestEntry> entries)
            throws IOException {
        JsonNode verdicts = cassette("verdict").get("deductions");
        JsonNode drafts = cassette("drafts").get("drafts");
        for (int index = 0; index < cases.size(); index++) {
            LoadedCase loaded = cases.get(index);
            ManifestEntry entry = entries.get(index);
            String caseId = loaded.facts().caseId();
            String deductionId = CorpusLoader.deductionId(caseId).toString();
            assertTrue(verdicts.has(deductionId), "the verdict cassette has no entry for " + caseId);
            boolean expectsDraft = loaded.expectation().expectedState() == VerdictValue.DISPUTABLE;
            if (expectsDraft && !drafts.has(deductionId)) {
                assertNotNull(entry.why(), "the negative control " + caseId + " must say why");
            } else {
                assertNull(entry.why(), caseId + " is a positive control and must leave why empty");
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
