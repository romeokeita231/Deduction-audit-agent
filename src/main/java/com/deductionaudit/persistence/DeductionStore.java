package com.deductionaudit.persistence;

import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.DeductionCategory;
import com.deductionaudit.domain.DeductionStatus;
import com.deductionaudit.domain.ParkedReason;
import com.deductionaudit.domain.VerdictRecord;
import com.deductionaudit.domain.VerdictValue;
import com.deductionaudit.domain.MissingEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DeductionStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public DeductionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Retailer(String id, String name, String timeZone, int windowDays) {
    }

    public record TransitionRow(int seq, DeductionStatus from, DeductionStatus to, String event,
                                Integer attemptNo, Instant occurredAt) {
    }

    public record AttemptRow(int attemptNo, Instant startedAt, Instant endedAt, String outcome,
                             int toolCallCount) {
    }

    public record DisputeRow(UUID id, UUID deductionId, Instant openedAt, Instant closedAt,
                             String result, String submittedBy, String note) {
    }

    public record DisputeDocumentRow(UUID disputeId, UUID deductionId, int attemptNo, JsonNode claim,
                                     String narrative, JsonNode digest, int evidenceVersion,
                                     String promptVersion, String model, Instant draftedAt) {
    }

    public record EvidenceVersion(int version, Instant updatedAt) {
    }

    public Optional<Deduction> find(UUID id) {
        List<Deduction> rows = jdbc.query("SELECT * FROM deduction WHERE id = ?", deductionMapper(), id);
        return rows.stream().findFirst();
    }

    public List<Deduction> findAll() {
        return jdbc.query("SELECT * FROM deduction", deductionMapper());
    }

    public List<Deduction> findByStatuses(List<DeductionStatus> statuses) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", statuses.stream().map(s -> "?").toList());
        return jdbc.query("SELECT * FROM deduction WHERE status IN (" + placeholders + ")",
                deductionMapper(),
                statuses.stream().map(Enum::name).toArray());
    }

    public void updateCategory(UUID id, DeductionCategory category) {
        jdbc.update("UPDATE deduction SET category = ? WHERE id = ?", category.name(), id);
    }

    public void lockForUpdate(UUID id) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "deduction:" + id);
    }

    public Optional<Retailer> findRetailer(String id) {
        List<Retailer> rows = jdbc.query("SELECT * FROM retailer WHERE id = ?",
                (rs, i) -> new Retailer(rs.getString("id"), rs.getString("name"),
                        rs.getString("time_zone"), rs.getInt("window_days")), id);
        return rows.stream().findFirst();
    }

    public List<Retailer> findAllRetailers() {
        return jdbc.query("SELECT * FROM retailer",
                (rs, i) -> new Retailer(rs.getString("id"), rs.getString("name"),
                        rs.getString("time_zone"), rs.getInt("window_days")));
    }

    public Optional<DeductionCategory> resolveCategory(String retailerId, String reasonCode) {
        List<String> rows = jdbc.queryForList(
                "SELECT category FROM reason_code_mapping WHERE retailer_id = ? AND reason_code = ? "
                        + "ORDER BY mapping_version DESC LIMIT 1",
                String.class, retailerId, reasonCode);
        return rows.stream().findFirst().map(DeductionCategory::valueOf);
    }

    public int mappingVersion(String retailerId, String reasonCode) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT mapping_version FROM reason_code_mapping WHERE retailer_id = ? AND reason_code = ? "
                        + "ORDER BY mapping_version DESC LIMIT 1",
                Integer.class, retailerId, reasonCode);
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    public void insertDeduction(Deduction d) {
        jdbc.update("""
                INSERT INTO deduction (id, retailer_id, reason_code, category, amount, currency, deduction_date,
                                       received_at, window_ends_at, status, parked_reason, parked_policy_version,
                                       invoice_no, po_no, shipment_id, sku, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                d.id(), d.retailerId(), d.reasonCode(),
                d.category() == null ? null : d.category().name(),
                d.amount(), d.currency(), d.deductionDate(), toOffset(d.receivedAt()), d.windowEndsAt(),
                d.status().name(), d.parkedReason() == null ? null : d.parkedReason().name(),
                d.parkedPolicyVersion(), d.invoiceNo(), d.poNo(), d.shipmentId(), d.sku(), d.version());
    }

    public boolean updateStatus(UUID id, long expectedVersion, DeductionStatus from, DeductionStatus to,
                                ParkedReason parkedReason, String parkedPolicyVersion) {
        int rows = jdbc.update("""
                        UPDATE deduction
                        SET status = ?, parked_reason = ?, parked_policy_version = ?, version = version + 1
                        WHERE id = ? AND version = ? AND status = ?
                        """,
                to.name(), parkedReason == null ? null : parkedReason.name(), parkedPolicyVersion,
                id, expectedVersion, from.name());
        return rows == 1;
    }

    public void appendTransition(UUID deductionId, DeductionStatus from, DeductionStatus to,
                                 String event, Integer attemptNo, Instant at) {
        jdbc.update("""
                        INSERT INTO deduction_transition (deduction_id, seq, from_status, to_status, event,
                                                          attempt_no, occurred_at)
                        SELECT ?, COALESCE(MAX(seq), 0) + 1, ?, ?, ?, ?, ? FROM deduction_transition
                        WHERE deduction_id = ?
                        """,
                deductionId, from == null ? null : from.name(), to.name(), event, attemptNo, toOffset(at),
                deductionId);
    }

    public List<TransitionRow> transitions(UUID deductionId) {
        return jdbc.query("""
                        SELECT * FROM deduction_transition WHERE deduction_id = ? ORDER BY seq
                        """,
                (rs, i) -> new TransitionRow(rs.getInt("seq"),
                        parseStatus(rs.getString("from_status")),
                        parseStatus(rs.getString("to_status")),
                        rs.getString("event"),
                        (Integer) rs.getObject("attempt_no"),
                        instant(rs, "occurred_at")), deductionId);
    }

    public int nextAttemptNo(UUID deductionId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(attempt_no), 0) FROM evaluation_attempt WHERE deduction_id = ?",
                Integer.class, deductionId);
        return (max == null ? 0 : max) + 1;
    }

    public void startAttempt(UUID deductionId, int attemptNo, Instant startedAt) {
        jdbc.update("""
                        INSERT INTO evaluation_attempt (deduction_id, attempt_no, started_at, tool_call_count)
                        VALUES (?, ?, ?, 0)
                        """,
                deductionId, attemptNo, toOffset(startedAt));
    }

    public void finishAttempt(UUID deductionId, int attemptNo, Instant endedAt, String outcome,
                              int toolCallCount) {
        jdbc.update("""
                        UPDATE evaluation_attempt
                        SET ended_at = ?, outcome = ?, tool_call_count = ?
                        WHERE deduction_id = ? AND attempt_no = ?
                        """,
                toOffset(endedAt), outcome, toolCallCount, deductionId, attemptNo);
    }

    public List<AttemptRow> attempts(UUID deductionId) {
        return jdbc.query("SELECT * FROM evaluation_attempt WHERE deduction_id = ? ORDER BY attempt_no",
                (rs, i) -> new AttemptRow(rs.getInt("attempt_no"),
                        instant(rs, "started_at"),
                        instant(rs, "ended_at"),
                        rs.getString("outcome"),
                        rs.getInt("tool_call_count")), deductionId);
    }

    public void insertVerdict(VerdictRecord v) {
        jdbc.update("""
                        INSERT INTO verdict (deduction_id, attempt_no, verdict, missing_evidence, reference_ids,
                                             supporting_evidence, reasoning, prompt_version, policy_version,
                                             mapping_version, model, evaluated_at)
                        VALUES (?, ?, ?, ?::text[], ?::text[], ?::text[], ?, ?, ?, ?, ?, ?)
                        """,
                v.deductionId(), v.attemptNo(), v.verdict().name(),
                arrayLiteral(v.missingEvidence()),
                arrayLiteralStrings(v.references()),
                arrayLiteralStrings(v.supportingEvidence()),
                v.reasoning(), v.promptVersion(), v.policyVersion(), v.mappingVersion(), v.model(),
                toOffset(v.evaluatedAt()));
    }

    public Optional<VerdictRecord> latestVerdict(UUID deductionId) {
        List<VerdictRecord> rows = jdbc.query(
                "SELECT * FROM verdict WHERE deduction_id = ? ORDER BY attempt_no DESC LIMIT 1",
                verdictMapper(), deductionId);
        return rows.stream().findFirst();
    }

    public List<VerdictRecord> verdicts(UUID deductionId) {
        return jdbc.query("SELECT * FROM verdict WHERE deduction_id = ? ORDER BY attempt_no",
                verdictMapper(), deductionId);
    }

    public java.util.Map<UUID, VerdictRecord> latestVerdicts() {
        java.util.Map<UUID, VerdictRecord> result = new java.util.HashMap<>();
        jdbc.query("""
                        SELECT DISTINCT ON (deduction_id) * FROM verdict
                        ORDER BY deduction_id, attempt_no DESC
                        """,
                rs -> {
                    VerdictRecord record = verdictMapper().mapRow(rs, 0);
                    result.put(record.deductionId(), record);
                });
        return result;
    }

    public Optional<DisputeRow> findDispute(UUID deductionId) {
        List<DisputeRow> rows = jdbc.query("SELECT * FROM dispute WHERE deduction_id = ?",
                (rs, i) -> new DisputeRow(rs.getObject("id", UUID.class), rs.getObject("deduction_id", UUID.class),
                        instant(rs, "opened_at"), instant(rs, "closed_at"), rs.getString("result"),
                        rs.getString("submitted_by"), rs.getString("note")), deductionId);
        return rows.stream().findFirst();
    }

    public UUID insertDispute(UUID deductionId, Instant openedAt, String submittedBy, String note) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO dispute (id, deduction_id, opened_at, submitted_by, note)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                id, deductionId, toOffset(openedAt), submittedBy, note);
        return id;
    }

    public void closeDispute(UUID deductionId, Instant closedAt, String result) {
        jdbc.update("UPDATE dispute SET closed_at = ?, result = ? WHERE deduction_id = ?",
                toOffset(closedAt), result, deductionId);
    }

    public void insertDisputeDocument(UUID disputeId, UUID deductionId, int attemptNo, JsonNode claim,
                                      String narrative, JsonNode digest, int evidenceVersion,
                                      String promptVersion, String model, Instant draftedAt) {
        jdbc.update("""
                        INSERT INTO dispute_document (dispute_id, deduction_id, attempt_no, claim, narrative,
                                                      digest, evidence_version, prompt_version, model, drafted_at)
                        VALUES (?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?)
                        """,
                disputeId, deductionId, attemptNo, json(claim), narrative, json(digest),
                evidenceVersion, promptVersion, model, toOffset(draftedAt));
    }

    public Optional<DisputeDocumentRow> findDisputeDocument(UUID deductionId) {
        List<DisputeDocumentRow> rows = jdbc.query(
                "SELECT * FROM dispute_document WHERE deduction_id = ?",
                (rs, i) -> new DisputeDocumentRow(
                        rs.getObject("dispute_id", UUID.class),
                        rs.getObject("deduction_id", UUID.class),
                        rs.getInt("attempt_no"),
                        jsonNode(rs, "claim"),
                        rs.getString("narrative"),
                        jsonNode(rs, "digest"),
                        rs.getInt("evidence_version"),
                        rs.getString("prompt_version"),
                        rs.getString("model"),
                        instant(rs, "drafted_at")), deductionId);
        return rows.stream().findFirst();
    }

    public EvidenceVersion evidenceVersion() {
        return jdbc.queryForObject("SELECT version, updated_at FROM evidence_version WHERE id = 1",
                (rs, i) -> new EvidenceVersion(rs.getInt("version"), instant(rs, "updated_at")));
    }

    private RowMapper<Deduction> deductionMapper() {
        return (rs, i) -> new Deduction(
                rs.getObject("id", UUID.class),
                rs.getString("retailer_id"),
                rs.getString("reason_code"),
                rs.getString("category") == null ? null : DeductionCategory.valueOf(rs.getString("category")),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getObject("deduction_date", LocalDate.class),
                instant(rs, "received_at"),
                rs.getObject("window_ends_at", LocalDate.class),
                DeductionStatus.valueOf(rs.getString("status")),
                rs.getString("parked_reason") == null ? null : ParkedReason.valueOf(rs.getString("parked_reason")),
                rs.getString("parked_policy_version"),
                rs.getString("invoice_no"),
                rs.getString("po_no"),
                rs.getString("shipment_id"),
                rs.getString("sku"),
                rs.getLong("version"));
    }

    private RowMapper<VerdictRecord> verdictMapper() {
        return (rs, i) -> new VerdictRecord(
                rs.getObject("deduction_id", UUID.class),
                rs.getInt("attempt_no"),
                VerdictValue.valueOf(rs.getString("verdict")),
                missingEvidence(rs),
                stringArray(rs, "reference_ids"),
                stringArray(rs, "supporting_evidence"),
                rs.getString("reasoning"),
                rs.getString("prompt_version"),
                rs.getString("policy_version"),
                rs.getInt("mapping_version"),
                rs.getString("model"),
                instant(rs, "evaluated_at"));
    }

    private static List<MissingEvidence> missingEvidence(ResultSet rs) throws SQLException {
        List<MissingEvidence> values = new ArrayList<>();
        for (String value : stringArray(rs, "missing_evidence")) {
            values.add(MissingEvidence.valueOf(value));
        }
        return values;
    }

    private static List<String> stringArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        String[] values = (String[]) array.getArray();
        return List.of(values);
    }

    private static String arrayLiteral(List<? extends Enum<?>> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        return "{" + values.stream().map(Enum::name).reduce((a, b) -> a + "," + b).orElse("") + "}";
    }

    private static String arrayLiteralStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        return "{" + String.join(",", values) + "}";
    }

    private static DeductionStatus parseStatus(String value) {
        return value == null ? null : DeductionStatus.valueOf(value);
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String json(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalArgumentException("json payload could not be serialized", e);
        }
    }

    private static JsonNode jsonNode(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        try {
            return JSON.readTree(value);
        } catch (IOException e) {
            throw new SQLException("column " + column + " does not hold json", e);
        }
    }
}
