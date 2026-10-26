package com.deductionaudit.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class EvidenceStore {

    private final JdbcTemplate jdbc;

    public EvidenceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AsnRow(String shipmentId, int declaredQty, Instant shippedAt, String poNo) {
    }

    public record BolRow(String shipmentId, int shippedQty, String carrier, Instant shippedAt) {
    }

    public record PodRow(String shipmentId, int receivedQty, Instant receivedAt, String signedBy) {
    }

    public record PriceRow(String sku, LocalDate validFrom, LocalDate validTo, BigDecimal unitPrice) {
    }

    public record InvoiceRow(String invoiceNo, String sku, int qty, BigDecimal unitPrice) {
    }

    public record ClauseRow(String docId, String clauseNo, String text, double similarity) {
    }

    public record SettlementRow(String docId, BigDecimal accruedAmount, BigDecimal settledAmount,
                                LocalDate settledOn) {
    }

    public Optional<AsnRow> asn(String shipmentId) {
        return jdbc.query("SELECT * FROM asn WHERE shipment_id = ?",
                (rs, i) -> new AsnRow(rs.getString("shipment_id"), rs.getInt("declared_qty"),
                        instant(rs, "shipped_at"), rs.getString("po_no")), shipmentId).stream().findFirst();
    }

    public Optional<BolRow> bol(String shipmentId) {
        return jdbc.query("SELECT * FROM bol WHERE shipment_id = ?",
                (rs, i) -> new BolRow(rs.getString("shipment_id"), rs.getInt("shipped_qty"),
                        rs.getString("carrier"), instant(rs, "shipped_at")), shipmentId).stream().findFirst();
    }

    public Optional<PodRow> pod(String shipmentId) {
        return jdbc.query("SELECT * FROM pod WHERE shipment_id = ?",
                (rs, i) -> new PodRow(rs.getString("shipment_id"), rs.getInt("received_qty"),
                        instant(rs, "received_at"), rs.getString("signed_by")), shipmentId).stream().findFirst();
    }

    public Optional<PriceRow> priceAt(String sku, LocalDate onDate) {
        return jdbc.query("""
                        SELECT * FROM price_master
                        WHERE sku = ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)
                        ORDER BY valid_from DESC LIMIT 1
                        """,
                (rs, i) -> priceRow(rs), sku, onDate, onDate).stream().findFirst();
    }

    public List<PriceRow> priceIntervals(String sku) {
        return jdbc.query("SELECT * FROM price_master WHERE sku = ? ORDER BY valid_from",
                (rs, i) -> priceRow(rs), sku);
    }

    public Optional<InvoiceRow> invoiceLine(String invoiceNo) {
        return jdbc.query("SELECT * FROM invoice_line WHERE invoice_no = ?",
                (rs, i) -> new InvoiceRow(rs.getString("invoice_no"), rs.getString("sku"),
                        rs.getInt("qty"), rs.getBigDecimal("unit_price")), invoiceNo).stream().findFirst();
    }

    public List<ClauseRow> clauses(String docId) {
        return jdbc.query("SELECT doc_id, clause_no, text, 0.0 AS similarity FROM promotion_agreement_clause "
                        + "WHERE doc_id = ? ORDER BY clause_no",
                (rs, i) -> new ClauseRow(rs.getString("doc_id"), rs.getString("clause_no"),
                        rs.getString("text"), 0.0), docId);
    }

    public List<ClauseRow> searchClauses(float[] queryVector, int limit) {
        String literal = vectorLiteral(queryVector);
        return jdbc.query("""
                        SELECT doc_id, clause_no, text, 1 - (embedding <=> ?::vector) AS similarity
                        FROM promotion_agreement_clause
                        WHERE embedding IS NOT NULL
                        ORDER BY embedding <=> ?::vector
                        LIMIT ?
                        """,
                (rs, i) -> new ClauseRow(rs.getString("doc_id"), rs.getString("clause_no"),
                        rs.getString("text"), rs.getDouble("similarity")),
                literal, literal, limit);
    }

    public List<ClauseRow> lexicalClauses(String query, int limit) {
        String pattern = "%" + (query == null ? "" : query.toLowerCase()) + "%";
        return jdbc.query("""
                        SELECT doc_id, clause_no, text, 0.0 AS similarity
                        FROM promotion_agreement_clause
                        WHERE lower(text) LIKE ?
                        ORDER BY doc_id, clause_no
                        LIMIT ?
                        """,
                (rs, i) -> new ClauseRow(rs.getString("doc_id"), rs.getString("clause_no"),
                        rs.getString("text"), 0.0), pattern, limit);
    }

    public Optional<SettlementRow> settlement(String docId) {
        return jdbc.query("SELECT * FROM promotion_settlement WHERE doc_id = ?",
                (rs, i) -> new SettlementRow(rs.getString("doc_id"), rs.getBigDecimal("accrued_amount"),
                        rs.getBigDecimal("settled_amount"),
                        rs.getObject("settled_on", LocalDate.class)), docId).stream().findFirst();
    }

    public void insertAgreement(String docId, String title, String retailerId, String sku,
                                LocalDate validFrom, LocalDate validTo) {
        jdbc.update("""
                        INSERT INTO promotion_agreement (doc_id, title, retailer_id, sku, valid_from, valid_to)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                docId, title, retailerId, sku, validFrom, validTo);
    }

    public void insertClause(String docId, String clauseNo, String text, float[] embedding) {
        jdbc.update("INSERT INTO promotion_agreement_clause (doc_id, clause_no, text, embedding) "
                        + "VALUES (?, ?, ?, ?::vector)",
                docId, clauseNo, text, embedding == null ? null : vectorLiteral(embedding));
    }

    public void insertSettlement(String docId, BigDecimal accrued, BigDecimal settled, LocalDate settledOn) {
        jdbc.update("INSERT INTO promotion_settlement (doc_id, accrued_amount, settled_amount, settled_on) "
                        + "VALUES (?, ?, ?, ?)",
                docId, accrued, settled, settledOn);
    }

    public void updateClauseEmbedding(String docId, String clauseNo, float[] embedding) {
        jdbc.update("UPDATE promotion_agreement_clause SET embedding = ?::vector "
                        + "WHERE doc_id = ? AND clause_no = ?",
                vectorLiteral(embedding), docId, clauseNo);
    }

    public int clauseCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM promotion_agreement_clause", Integer.class);
        return count == null ? 0 : count;
    }

    public int agreementCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM promotion_agreement", Integer.class);
        return count == null ? 0 : count;
    }

    private static PriceRow priceRow(ResultSet rs) throws SQLException {
        return new PriceRow(rs.getString("sku"),
                rs.getObject("valid_from", LocalDate.class),
                rs.getObject("valid_to", LocalDate.class),
                rs.getBigDecimal("unit_price"));
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
