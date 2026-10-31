package com.deductionaudit.eval;

import com.deductionaudit.IntegrationTestBase;
import com.deductionaudit.domain.Claim;
import com.deductionaudit.domain.DeductionCategory;
import com.deductionaudit.domain.VerdictValue;
import com.deductionaudit.eval.corpus.CorpusLoader;
import com.deductionaudit.eval.corpus.CorpusLoader.LoadedCase;
import com.deductionaudit.eval.corpus.EvidenceToken;
import com.deductionaudit.shared.PromptInspection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalCaseDriverTest extends IntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock FIXED = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final List<String> CASE_IDS = List.of(
            "SHORTAGE-SUPPORTS_RETAILER-01",
            "SHORTAGE-SUPPORTS_SUPPLIER-01",
            "PRICE-EVIDENCE_MISSING-01");

    @TestConfiguration
    static class StubModel {

        @Bean
        @Primary
        StubChatModel stubChatModel() {
            return new StubChatModel();
        }
    }

    @Autowired
    private CorpusLoader corpus;

    @Autowired
    private EvalCaseDriver driver;

    @Autowired
    private StubChatModel stub;

    @Test
    void threeCasesProduceSelfContainedNdjson(@TempDir Path directory) throws IOException {
        List<LoadedCase> cases = cases();
        stub.register(cases);

        List<NdjsonExporter.EvalRow> rows = driver.runAll(cases, 3);
        assertEquals(3, rows.size());
        for (int i = 0; i < cases.size(); i++) {
            assertEquals(cases.get(i).facts().caseId(), rows.get(i).caseId());
        }

        List<String> lines = rows.stream().map(NdjsonExporter::line).toList();
        for (String line : lines) {
            JsonNode node = JSON.readTree(line);
            assertEquals(NdjsonExporter.SCHEMA_VERSION, node.get("schemaVersion").asInt());
            assertTrue(node.has("caseId") && node.has("status") && node.has("toolCalls"));
            assertTrue(node.has("contract") && node.has("expectedState"));
            assertTrue(node.get("expectedSupportingEvidence").isArray());
        }

        NdjsonExporter.EvalRow disputable = byCase(rows, "SHORTAGE-SUPPORTS_RETAILER-01");
        assertEquals("DISPUTABLE", disputable.verdict());
        assertEquals("DISPUTED", disputable.status());
        assertNotNull(disputable.claim());
        assertNotNull(disputable.narrative());
        assertNotNull(disputable.digest());
        assertEquals("DISPUTABLE", disputable.expectedState());

        NdjsonExporter.EvalRow stands = byCase(rows, "SHORTAGE-SUPPORTS_SUPPLIER-01");
        assertEquals("DEDUCTION_STANDS", stands.verdict());
        assertEquals("AWAITING_ACTION", stands.status());

        NdjsonExporter.EvalRow inconclusive = byCase(rows, "PRICE-EVIDENCE_MISSING-01");
        assertEquals("INCONCLUSIVE", inconclusive.verdict());
        assertEquals("EVIDENCE_GAP", inconclusive.parkedReason());
        assertEquals("EVIDENCE_GAP", inconclusive.expectedParkedReason());
        assertNotNull(inconclusive.expectedState());

        NdjsonExporter.Contract contract = disputable.contract();
        assertEquals(120, contract.summaryWordsMin());
        assertEquals(250, contract.summaryWordsMax());
        assertEquals(3, contract.paragraphsMin());
        assertEquals(5, contract.paragraphsMax());
        assertEquals("whitespace", contract.tokenizer());
        assertEquals(disputable.claim().get("amount").asText(), contract.amount());

        String baseline = NdjsonExporter.baselineLine(disputable);
        JsonNode baselineNode = JSON.readTree(baseline);
        assertFalse(baselineNode.has("latency"));
        assertFalse(baselineNode.get("toolCalls").get(0).has("durationMs"));
        assertTrue(baselineNode.get("toolCalls").get(0).has("name"));
        assertTrue(baselineNode.get("toolCalls").get(0).has("count"));
        assertTrue(baselineNode.has("expectedSupportingEvidence"));

        Path export = directory.resolve("results.ndjson");
        NdjsonExporter.write(export, rows, false);
        List<String> written = Files.readAllLines(export, StandardCharsets.UTF_8);
        assertEquals(3, written.size());
        NdjsonExporter.write(directory.resolve("baseline.ndjson"), rows, true);
        for (String line : Files.readAllLines(directory.resolve("baseline.ndjson"), StandardCharsets.UTF_8)) {
            if (line.isEmpty()) {
                continue;
            }
            assertFalse(JSON.readTree(line).has("latency"));
        }

        assertTrue(stub.peakConcurrency() >= 2, "the driver ran the cases serially");
    }

    @Test
    void draftFailuresBecomeErrorRowsWithoutAbortingTheBatch() {
        List<LoadedCase> disputable = corpus.load(FIXED).stream()
                .filter(loaded -> loaded.expectation().expectedState() == VerdictValue.DISPUTABLE)
                .limit(2)
                .toList();
        assertEquals(2, disputable.size());
        stub.reset();
        stub.register(disputable);
        UUID unavailable = disputable.get(0).deduction().id();
        UUID inconsistent = disputable.get(1).deduction().id();
        stub.failDraft(unavailable);
        stub.corruptDraft(inconsistent);

        List<NdjsonExporter.EvalRow> rows = driver.runAll(disputable, 2);
        assertEquals(2, rows.size());
        NdjsonExporter.EvalRow missing = byCase(rows, disputable.get(0).facts().caseId());
        NdjsonExporter.EvalRow invalid = byCase(rows, disputable.get(1).facts().caseId());
        assertEquals(EvalCaseDriver.DRAFT_UNAVAILABLE_ERROR, missing.error());
        assertNull(missing.narrative());
        assertEquals(EvalCaseDriver.DRAFT_INCONSISTENT_ERROR, invalid.error());
        assertNull(invalid.narrative());
        assertEquals("AWAITING_ACTION", missing.status());
        assertEquals("AWAITING_ACTION", invalid.status());
        assertEquals(1, stub.draftCalls(unavailable));
        assertEquals(1, stub.draftCalls(inconsistent));
    }

    @Test
    void theRowCountAssertionNamesExpectedAndActual() {
        EvalCaseDriver.assertRowCount(List.of(), 0);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> EvalCaseDriver.assertRowCount(List.of(), 15));
        assertTrue(error.getMessage().contains("0"));
        assertTrue(error.getMessage().contains("15"));
    }

    @Test
    void theRowCountAssertionUsesTheSmallerOfLimitAndCorpusSize() {
        int total = corpus.load(FIXED).size();
        assertEquals(105, total);
        List<NdjsonExporter.EvalRow> five = Collections.nCopies(5, (NdjsonExporter.EvalRow) null);
        EvalCaseDriver.assertRowCount(five, EvalCaseDriver.expectedRowCount(5, total));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> EvalCaseDriver.assertRowCount(five, EvalCaseDriver.expectedRowCount(null, total)));
        assertTrue(error.getMessage().contains("5"));
        assertTrue(error.getMessage().contains("105"));
    }

    private List<LoadedCase> cases() {
        List<LoadedCase> all = corpus.load(FIXED);
        List<LoadedCase> selected = new ArrayList<>();
        for (LoadedCase loaded : all) {
            if (CASE_IDS.contains(loaded.facts().caseId())) {
                selected.add(loaded);
            }
        }
        assertEquals(CASE_IDS.size(), selected.size());
        return selected;
    }

    private static NdjsonExporter.EvalRow byCase(List<NdjsonExporter.EvalRow> rows, String caseId) {
        return rows.stream().filter(row -> caseId.equals(row.caseId())).findFirst().orElseThrow();
    }

    static final class StubChatModel implements ChatModel {

        private final Map<UUID, LoadedCase> cases = new ConcurrentHashMap<>();
        private final Map<UUID, RuntimeException> unavailable = new ConcurrentHashMap<>();
        private final Map<UUID, Boolean> inconsistent = new ConcurrentHashMap<>();
        private final Map<UUID, AtomicInteger> drafts = new ConcurrentHashMap<>();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();

        void register(List<LoadedCase> loaded) {
            for (LoadedCase entry : loaded) {
                cases.put(entry.deduction().id(), entry);
            }
        }

        void reset() {
            cases.clear();
            unavailable.clear();
            inconsistent.clear();
            drafts.clear();
        }

        void failDraft(UUID id) {
            unavailable.put(id, new RuntimeException("stub model unavailable"));
        }

        void corruptDraft(UUID id) {
            inconsistent.put(id, true);
        }

        int draftCalls(UUID id) {
            return drafts.getOrDefault(id, new AtomicInteger()).get();
        }

        int peakConcurrency() {
            return peak.get();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            int running = active.incrementAndGet();
            peak.accumulateAndGet(running, Math::max);
            try {
                sleep(40);
                if (PromptInspection.isDraft(prompt)) {
                    return draft(prompt);
                }
                return verdict(prompt);
            } finally {
                active.decrementAndGet();
            }
        }

        private ChatResponse verdict(Prompt prompt) {
            LoadedCase loaded = cases.get(PromptInspection.deductionId(prompt));
            long rounds = prompt.getInstructions().stream()
                    .filter(message -> message.getMessageType() == MessageType.ASSISTANT)
                    .count();
            return rounds == 0 ? tools(loaded) : text(verdictJson(loaded));
        }

        private ChatResponse tools(LoadedCase loaded) {
            String shipmentId = loaded.facts().evidence().shipmentId();
            List<AssistantMessage.ToolCall> calls = new ArrayList<>();
            switch (loaded.deduction().category()) {
                case SHORTAGE -> {
                    calls.add(call("getAsn", "{\"shipmentId\":\"" + shipmentId + "\"}"));
                    calls.add(call("getBol", "{\"shipmentId\":\"" + shipmentId + "\"}"));
                    calls.add(call("getPod", "{\"shipmentId\":\"" + shipmentId + "\"}"));
                }
                case PRICE_VARIANCE -> {
                    calls.add(call("getPriceMaster", "{\"sku\":\"" + loaded.deduction().sku()
                            + "\",\"onDate\":\"" + loaded.deduction().deductionDate() + "\"}"));
                    calls.add(call("getInvoiceLine", "{\"invoiceNo\":\"" + loaded.deduction().invoiceNo()
                            + "\"}"));
                }
                case PROMOTION -> {
                    String docId = loaded.facts().evidence().agreements().getFirst().docId();
                    calls.add(call("searchPromotionAgreements", "{\"query\":\"" + docId + "\"}"));
                    calls.add(call("getPromotionSettlement", "{\"docId\":\"" + docId + "\"}"));
                }
            }
            return ChatResponse.builder()
                    .generations(List.of(new Generation(
                            AssistantMessage.builder().toolCalls(calls).build())))
                    .metadata("model", "stub")
                    .build();
        }

        private static AssistantMessage.ToolCall call(String name, String arguments) {
            return new AssistantMessage.ToolCall("stub-" + name, "function", name, arguments);
        }

        private ChatResponse draft(Prompt prompt) {
            UUID id = PromptInspection.deductionId(prompt);
            drafts.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
            RuntimeException failure = unavailable.get(id);
            if (failure != null) {
                throw failure;
            }
            Claim claim = claim(prompt);
            String narrative = inconsistent.containsKey(id) ? "# not a letter" : narrative(claim);
            return text(JSON.createObjectNode().put("narrative", narrative).toString());
        }

        private static Claim claim(Prompt prompt) {
            for (Message message : prompt.getInstructions()) {
                String text = message.getText();
                if (text == null || !text.startsWith("Claim:\n")) {
                    continue;
                }
                String body = text.substring("Claim:\n".length());
                int digest = body.indexOf("\n\nEvidence digest:");
                String json = digest < 0 ? body : body.substring(0, digest);
                try {
                    return JSON.readValue(json, Claim.class);
                } catch (IOException e) {
                    throw new IllegalStateException("the stub cannot read the claim", e);
                }
            }
            throw new IllegalStateException("the draft prompt carries no claim");
        }

        private static String narrative(Claim claim) {
            String category = claim.category().toLowerCase().replace('_', ' ');
            String evidence = claim.supportingEvidence().isEmpty()
                    ? claim.references().getFirst() : String.join(", ", claim.supportingEvidence());
            return "This letter concerns the " + category + " deduction of " + claim.amount() + " "
                    + claim.currency() + " raised under " + claim.references().getFirst()
                    + ". We reviewed the record the retailer attached to the claim, and we conclude "
                    + "that the deduction is not supported.\n\n"
                    + "The supporting evidence " + evidence + " shows that the deduction does not rest "
                    + "on the documents the retailer relies on. Each identifier resolves to a record we "
                    + "have already checked, and none of them supports the amount that was withheld.\n\n"
                    + "Because the deduction is not supported, we ask you to reverse it in full and to "
                    + "issue a credit memo for the same amount to our account.\n\n"
                    + "Please confirm the reversal in writing. We remain available to provide any further "
                    + "documentation you may need, and we look forward to closing this case promptly.";
        }

        private static String verdictJson(LoadedCase loaded) {
            List<String> missing = new ArrayList<>();
            if (loaded.facts().evidence().bol() == null) {
                missing.add("BOL");
            }
            if (loaded.facts().evidence().pod() == null) {
                missing.add("POD");
            }
            if (loaded.facts().evidence().invoiceLine() == null
                    && loaded.deduction().category() == DeductionCategory.PRICE_VARIANCE) {
                missing.add("INVOICE_LINE");
            }
            if (loaded.facts().evidence().settlement() == null
                    && loaded.deduction().category() == DeductionCategory.PROMOTION) {
                missing.add("PROMOTION_SETTLEMENT");
            }
            String supporting = loaded.expectation().expectedSupportingEvidence().stream()
                    .map(token -> "\"" + EvidenceToken.resolve(token, FIXED) + "\"")
                    .collect(Collectors.joining(","));
            return "{\"verdict\":\"" + loaded.expectation().expectedState().name()
                    + "\",\"missingEvidence\":[" + missing.stream().map(value -> "\"" + value + "\"")
                    .collect(Collectors.joining(",")) + "]"
                    + ",\"references\":[\"EDI812:" + loaded.deduction().id() + "\"]"
                    + ",\"supportingEvidence\":[" + supporting + "]"
                    + ",\"reasoning\":\"stub verdict for the evaluation fixture\"}";
        }

        private static ChatResponse text(String content) {
            return ChatResponse.builder()
                    .generations(List.of(new Generation(
                            AssistantMessage.builder().content(content).build())))
                    .metadata("model", "stub")
                    .build();
        }

        private static void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("the stub model was interrupted", e);
            }
        }
    }
}
