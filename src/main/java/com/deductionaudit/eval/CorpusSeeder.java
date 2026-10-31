package com.deductionaudit.eval;

import com.deductionaudit.domain.Deduction;
import com.deductionaudit.eval.corpus.CorpusLoader;
import com.deductionaudit.eval.corpus.CorpusLoader.LoadedCase;
import com.deductionaudit.persistence.DeductionStore;
import com.deductionaudit.persistence.EvidenceStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Component
public class CorpusSeeder {

    private final JdbcTemplate jdbc;
    private final DeductionStore deductions;
    private final EvidenceStore evidence;

    public CorpusSeeder(JdbcTemplate jdbc, DeductionStore deductions, EvidenceStore evidence) {
        this.jdbc = jdbc;
        this.deductions = deductions;
        this.evidence = evidence;
    }

    public void seed(LoadedCase loaded) {
        Deduction deduction = loaded.deduction();
        clear(deduction);
        deductions.insertDeduction(deduction);
        if (loaded.asn() != null) {
            jdbc.update("INSERT INTO asn (shipment_id, declared_qty, shipped_at, po_no) VALUES (?, ?, ?, ?)",
                    loaded.asn().shipmentId(), loaded.asn().declaredQty(),
                    offset(loaded.asn().shippedAt()), loaded.asn().poNo());
        }
        if (loaded.bol() != null) {
            jdbc.update("INSERT INTO bol (shipment_id, shipped_qty, carrier, shipped_at) VALUES (?, ?, ?, ?)",
                    loaded.bol().shipmentId(), loaded.bol().shippedQty(), loaded.bol().carrier(),
                    offset(loaded.bol().shippedAt()));
        }
        if (loaded.pod() != null) {
            jdbc.update("INSERT INTO pod (shipment_id, received_qty, received_at, signed_by) VALUES (?, ?, ?, ?)",
                    loaded.pod().shipmentId(), loaded.pod().receivedQty(), offset(loaded.pod().receivedAt()),
                    loaded.pod().signedBy());
        }
        for (EvidenceStore.PriceRow interval : loaded.prices()) {
            jdbc.update("INSERT INTO price_master (sku, valid_from, valid_to, unit_price) VALUES (?, ?, ?, ?)",
                    interval.sku(), interval.validFrom(), interval.validTo(), interval.unitPrice());
        }
        if (loaded.invoice() != null) {
            jdbc.update("INSERT INTO invoice_line (invoice_no, sku, qty, unit_price) VALUES (?, ?, ?, ?)",
                    loaded.invoice().invoiceNo(), loaded.invoice().sku(), loaded.invoice().qty(),
                    loaded.invoice().unitPrice());
        }
        for (LoadedCase.Agreement agreement : loaded.agreements()) {
            evidence.insertAgreement(agreement.docId(), agreement.title(), agreement.retailerId(),
                    agreement.sku(), agreement.validFrom(), agreement.validTo());
            for (EvidenceStore.ClauseRow clause : agreement.clauses()) {
                evidence.insertClause(clause.docId(), clause.clauseNo(), clause.text(), null);
            }
        }
        if (loaded.settlement() != null) {
            evidence.insertSettlement(loaded.settlement().docId(), loaded.settlement().accruedAmount(),
                    loaded.settlement().settledAmount(), loaded.settlement().settledOn());
        }
    }

    private void clear(Deduction deduction) {
        jdbc.update("DELETE FROM dispute_document WHERE deduction_id = ?", deduction.id());
        jdbc.update("DELETE FROM dispute WHERE deduction_id = ?", deduction.id());
        jdbc.update("DELETE FROM verdict WHERE deduction_id = ?", deduction.id());
        jdbc.update("DELETE FROM evaluation_attempt WHERE deduction_id = ?", deduction.id());
        jdbc.update("DELETE FROM deduction_transition WHERE deduction_id = ?", deduction.id());
        jdbc.update("DELETE FROM deduction WHERE id = ?", deduction.id());
        if (deduction.shipmentId() != null) {
            jdbc.update("DELETE FROM asn WHERE shipment_id = ?", deduction.shipmentId());
            jdbc.update("DELETE FROM bol WHERE shipment_id = ?", deduction.shipmentId());
            jdbc.update("DELETE FROM pod WHERE shipment_id = ?", deduction.shipmentId());
        }
        if (deduction.sku() != null) {
            jdbc.update("DELETE FROM price_master WHERE sku = ?", deduction.sku());
            jdbc.update("""
                    DELETE FROM promotion_settlement
                    WHERE doc_id IN (SELECT doc_id FROM promotion_agreement WHERE retailer_id = ? AND sku = ?)
                    """, deduction.retailerId(), deduction.sku());
            jdbc.update("""
                    DELETE FROM promotion_agreement_clause
                    WHERE doc_id IN (SELECT doc_id FROM promotion_agreement WHERE retailer_id = ? AND sku = ?)
                    """, deduction.retailerId(), deduction.sku());
            jdbc.update("DELETE FROM promotion_agreement WHERE retailer_id = ? AND sku = ?",
                    deduction.retailerId(), deduction.sku());
        }
        if (deduction.invoiceNo() != null) {
            jdbc.update("DELETE FROM invoice_line WHERE invoice_no = ?", deduction.invoiceNo());
        }
    }

    private static OffsetDateTime offset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
