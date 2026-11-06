package com.deductionaudit.eval.corpus;

import com.deductionaudit.domain.DeductionCategory;

import java.math.BigDecimal;
import java.util.List;

public record CorpusCase(
        String caseId,
        EvalCell cell,
        long seed,
        String retailerId,
        String reasonCode,
        BigDecimal amount,
        String currency,
        int receivedDaysAgo,
        int deductionDaysAgo,
        int windowDays,
        Evidence evidence) {

    public CorpusCase {
        evidence = evidence == null ? Evidence.NONE : evidence;
    }

    public record EvalCell(DeductionCategory category, Situation situation) {
    }

    public record Evidence(
            String shipmentId,
            String sku,
            String invoiceNo,
            Asn asn,
            Bol bol,
            Pod pod,
            List<PriceInterval> priceIntervals,
            InvoiceLine invoiceLine,
            List<Agreement> agreements,
            Settlement settlement) {

        public static final Evidence NONE = new Evidence(null, null, null, null, null, null,
                List.of(), null, List.of(), null);

        public Evidence {
            priceIntervals = priceIntervals == null ? List.of() : List.copyOf(priceIntervals);
            agreements = agreements == null ? List.of() : List.copyOf(agreements);
        }

        public static Evidence shipment(String shipmentId, Asn asn, Bol bol, Pod pod) {
            return new Evidence(shipmentId, null, null, asn, bol, pod, List.of(), null, List.of(), null);
        }

        public static Evidence price(String sku, String invoiceNo, List<PriceInterval> intervals,
                                     InvoiceLine line) {
            return new Evidence(null, sku, invoiceNo, null, null, null, intervals, line, List.of(), null);
        }

        public static Evidence promotion(String sku, List<Agreement> agreements, Settlement settlement) {
            return new Evidence(null, sku, null, null, null, null, List.of(), null, agreements, settlement);
        }
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
