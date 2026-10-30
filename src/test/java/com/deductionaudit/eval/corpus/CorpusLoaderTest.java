package com.deductionaudit.eval.corpus;

import com.deductionaudit.domain.Deduction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorpusLoaderTest {

    private static final Instant NOW = Instant.parse("2026-03-10T06:59:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static CorpusLoader loader() {
        return new CorpusLoader(new DefaultResourceLoader());
    }

    @Test
    void fixedAndRealClockResolveTheExpectedWindow() {
        List<CorpusLoader.LoadedCase> fixed = loader().load(FIXED);
        List<CorpusLoader.LoadedCase> real = loader().load(Clock.systemUTC());
        assertFalse(fixed.isEmpty());

        for (int i = 0; i < fixed.size(); i++) {
            CorpusCase facts = fixed.get(i).facts();
            assertWindow(fixed.get(i).deduction(), facts, NOW);
            assertWindow(real.get(i).deduction(), facts, real.get(i).deduction().receivedAt()
                    .plus(Duration.ofDays(facts.receivedDaysAgo())));
        }
    }

    @Test
    void timezoneDoesNotChangeInstantsOrIds() {
        List<CorpusLoader.LoadedCase> utc = loader().load(FIXED);
        List<CorpusLoader.LoadedCase> tokyo = loader().load(
                Clock.fixed(NOW, ZoneId.of("Asia/Tokyo")));
        assertEquals(utc.size(), tokyo.size());
        for (int i = 0; i < utc.size(); i++) {
            Deduction a = utc.get(i).deduction();
            Deduction b = tokyo.get(i).deduction();
            assertEquals(a.id(), b.id());
            assertEquals(a.receivedAt(), b.receivedAt());
            assertEquals(a.deductionDate(), b.deductionDate());
            assertEquals(a.windowEndsAt(), b.windowEndsAt());
        }
    }

    @Test
    void caseIdDerivesAGoldenUuid() {
        assertEquals(UUID.fromString("ae3c4fc3-297c-3120-9590-1e46af9ef6c8"),
                CorpusLoader.deductionId("SHORTAGE-SUPPORTS_RETAILER-01"));
    }

    @Test
    void everyFileCarriesEveryArrayField() throws IOException {
        Path root = Path.of("src/main/resources/eval/corpus");
        try (Stream<Path> files = Files.list(root)) {
            List<Path> corpus = files.filter(path -> path.toString().endsWith(".json")).toList();
            assertFalse(corpus.isEmpty());
            for (Path file : corpus) {
                JsonNode node = JSON.readTree(file.toFile());
                JsonNode evidence = node.get("facts").get("evidence");
                for (String field : List.of("priceIntervals", "agreements")) {
                    assertTrue(evidence.has(field), file.getFileName() + " omits " + field);
                    assertTrue(evidence.get(field).isArray(), file.getFileName() + " " + field + " is not an array");
                }
                JsonNode expected = node.get("expectation").get("expectedSupportingEvidence");
                assertTrue(expected.isArray(), file.getFileName() + " expectedSupportingEvidence is not an array");
            }
        }
    }

    @Test
    void deferredPriceTokensRoundTripAgainstTheCommittedCorpus() {
        int tokens = 0;
        for (CorpusLoader.LoadedCase loaded : loader().load(FIXED)) {
            for (String token : loaded.expectation().expectedSupportingEvidence()) {
                EvidenceToken.PriceOffset offset = EvidenceToken.parsePrice(token);
                if (offset == null) {
                    continue;
                }
                assertEquals(token, offset.token(), loaded.facts().caseId());
                tokens++;
            }
        }
        assertEquals(21, tokens, "the committed corpus holds twenty-one deferred price tokens");
    }

    @Test
    void onlyOneCorpusLoaderExists() throws IOException {
        List<String> owners = new ArrayList<>();
        for (Path root : List.of(Path.of("src/main/java"), Path.of("src/test/java"))) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    if (Files.readString(file).contains("classpath:" + "eval/corpus")) {
                        owners.add(file.getFileName().toString());
                    }
                }
            }
        }
        assertEquals(List.of("CorpusLoader.java"), owners);
    }

    private static void assertWindow(Deduction deduction, CorpusCase facts, Instant now) {
        assertEquals(facts.windowDays(),
                ChronoUnit.DAYS.between(deduction.deductionDate(), deduction.windowEndsAt()));
        assertEquals(now.minus(Duration.ofDays(facts.receivedDaysAgo())), deduction.receivedAt());
        assertEquals(now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(facts.deductionDaysAgo()),
                deduction.deductionDate());
    }
}
