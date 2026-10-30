package com.deductionaudit.eval.corpus;

import com.deductionaudit.domain.DeductionCategory;
import com.deductionaudit.domain.ParkedReason;
import com.deductionaudit.domain.VerdictValue;

import java.math.BigDecimal;
import java.util.List;

public record CorpusCase(
        String caseId,
        Matrix matrix,
        long seed,
        String retailerId,
        String reasonCode,
        BigDecimal amount,
        String currency,
        int receivedDaysAgo,
        int deductionDaysAgo,
        int windowDays,
        String shipmentId,
        String sku,
        String invoiceNo,
        Asn asn,
        Bol bol,
        Pod pod,
        List<PriceInterval> priceIntervals,
        InvoiceLine invoiceLine,
        List<Agreement> agreements,
        Settlement settlement,
        VerdictValue expectedState,
        ParkedReason expectedParkedReason,
        List<String> expectedSupportingEvidence,
        String oracleBranch) {

    public CorpusCase {
        priceIntervals = priceIntervals == null ? List.of() : List.copyOf(priceIntervals);
        agreements = agreements == null ? List.of() : List.copyOf(agreements);
        expectedSupportingEvidence = expectedSupportingEvidence == null
                ? List.of() : List.copyOf(expectedSupportingEvidence);
    }

    public record Matrix(DeductionCategory category, Situation situation) {
    }

    public record Asn(int declaredQty, int shippedDaysAgo) {
    }

    public record Bol(int shippedQty, int shippedDaysAgo) {
    }

    public record Pod(int receivedQty, int receivedDaysAgo) {
    }

    public record PriceInterval(int validFromDaysAgo, Integer validToDaysAgo, BigDecimal unitPrice) {
    }

    public record InvoiceLine(int qty, BigDecimal unitPrice) {
    }

    public record Agreement(String docId, int validFromDaysAgo, Integer validToDaysAgo,
                            List<Clause> clauses) {
    }

    public record Clause(String clauseNo, String text, ClauseEffect effect) {
    }

    public record Settlement(String docId, BigDecimal accruedAmount, BigDecimal settledAmount,
                             int settledDaysAgo) {
    }

    public enum Situation {
        SUPPORTS_SUPPLIER,
        SUPPORTS_RETAILER,
        EVIDENCE_MISSING,
        CONFLICT_ARBITRATED,
        CONFLICT_UNARBITRATED
    }

    public enum ClauseEffect {
        SUPPORTS_ACCRUAL,
        DENIES_ACCRUAL,
        CONFLICTS_WITH_SUMMARY,
        NEUTRAL
    }
}
