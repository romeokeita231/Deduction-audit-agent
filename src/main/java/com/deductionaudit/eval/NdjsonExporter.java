package com.deductionaudit.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public final class NdjsonExporter {

    public static final int SCHEMA_VERSION = 2;

    static final int SUMMARY_WORDS_MIN = 120;
    static final int SUMMARY_WORDS_MAX = 250;
    static final int PARAGRAPHS_MIN = 3;
    static final int PARAGRAPHS_MAX = 5;
    static final String TOKENIZER = "whitespace";

    private static final ObjectMapper JSON = new ObjectMapper();

    private NdjsonExporter() {
    }

    public record ToolCall(String name, int count, long durationMs) {
    }

    public record Contract(int summaryWordsMin, int summaryWordsMax, int paragraphsMin, int paragraphsMax,
                           String tokenizer, String amount) {
    }

    public record EvalRow(int schemaVersion, String caseId, int attemptNo, String status, String parkedReason,
                          String verdict, JsonNode claim, String narrative, String error, JsonNode digest,
                          long latencyMs, List<ToolCall> toolCalls, Contract contract, String oracleBranch,
                          String expectedState, String expectedParkedReason,
                          List<String> expectedSupportingEvidence) {
    }

    public static Contract contract(String amount) {
        return new Contract(SUMMARY_WORDS_MIN, SUMMARY_WORDS_MAX, PARAGRAPHS_MIN, PARAGRAPHS_MAX, TOKENIZER,
                amount);
    }

    public static String line(EvalRow row) {
        return serialize(node(row, false));
    }

    public static String baselineLine(EvalRow row) {
        return serialize(node(row, true));
    }

    public static void write(Path path, List<EvalRow> rows, boolean baseline) {
        String body = rows.stream()
                .map(baseline ? NdjsonExporter::baselineLine : NdjsonExporter::line)
                .collect(Collectors.joining("\n")) + "\n";
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("the ndjson export cannot be written to " + path, e);
        }
    }

    private static ObjectNode node(EvalRow row, boolean baseline) {
        ObjectNode node = JSON.createObjectNode();
        node.put("schemaVersion", row.schemaVersion());
        node.put("caseId", row.caseId());
        node.put("attemptNo", row.attemptNo());
        node.put("status", row.status());
        putNullable(node, "parkedReason", row.parkedReason());
        putNullable(node, "verdict", row.verdict());
        node.set("claim", row.claim());
        putNullable(node, "narrative", row.narrative());
        putNullable(node, "error", row.error());
        node.set("digest", row.digest());
        if (!baseline) {
            node.put("latency", row.latencyMs());
        }
        ArrayNode calls = node.putArray("toolCalls");
        for (ToolCall call : row.toolCalls()) {
            ObjectNode entry = calls.addObject();
            entry.put("name", call.name());
            entry.put("count", call.count());
            if (!baseline) {
                entry.put("durationMs", call.durationMs());
            }
        }
        ObjectNode contract = node.putObject("contract");
        ObjectNode words = contract.putObject("summaryWords");
        words.put("min", row.contract().summaryWordsMin());
        words.put("max", row.contract().summaryWordsMax());
        ObjectNode paragraphs = contract.putObject("paragraphs");
        paragraphs.put("min", row.contract().paragraphsMin());
        paragraphs.put("max", row.contract().paragraphsMax());
        contract.put("tokenizer", row.contract().tokenizer());
        contract.put("amount", row.contract().amount());
        node.put("oracleBranch", row.oracleBranch());
        node.put("expectedState", row.expectedState());
        putNullable(node, "expectedParkedReason", row.expectedParkedReason());
        ArrayNode expected = node.putArray("expectedSupportingEvidence");
        row.expectedSupportingEvidence().forEach(expected::add);
        return node;
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static String serialize(ObjectNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (IOException e) {
            throw new UncheckedIOException("the ndjson row cannot be serialized", e);
        }
    }
}
