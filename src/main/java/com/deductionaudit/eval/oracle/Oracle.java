package com.deductionaudit.eval.oracle;

import com.deductionaudit.agent.PolicyProvider;
import com.deductionaudit.domain.EvidenceIds;
import com.deductionaudit.domain.ParkedReason;
import com.deductionaudit.domain.VerdictValue;
import com.deductionaudit.eval.corpus.CorpusCase;
import com.deductionaudit.eval.corpus.CorpusCase.ClauseEffect;
import com.deductionaudit.eval.corpus.CorpusCase.PriceInterval;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class Oracle {

    public static final String POLICY_SHA256 =
            "3dde491d3d993fb2dbaa3a9d727e03864285fc8e223a25179ed614a5d89fc2d8";
    public static final String POLICY_VERSION = POLICY_SHA256.substring(0, 12);

    public static final String SHORTAGE_POD_EQUALS_BOL = "SHORTAGE_POD_EQUALS_BOL";
    public static final String SHORTAGE_POD_BELOW_BOL = "SHORTAGE_POD_BELOW_BOL";
    public static final String SHORTAGE_MISSING_BOL = "SHORTAGE_MISSING_BOL";
    public static final String SHORTAGE_MISSING_POD = "SHORTAGE_MISSING_POD";
    public static final String SHORTAGE_ASN_BOL_DISAGREE_BOL_WINS = "SHORTAGE_ASN_BOL_DISAGREE_BOL_WINS";
    public static final String SHORTAGE_POD_ABOVE_BOL = "SHORTAGE_POD_ABOVE_BOL";

    public static final String PRICE_INVOICE_BELOW_MASTER = "PRICE_INVOICE_BELOW_MASTER";
    public static final String PRICE_INVOICE_AT_OR_ABOVE_MASTER = "PRICE_INVOICE_AT_OR_ABOVE_MASTER";
    public static final String PRICE_MISSING_INVOICE = "PRICE_MISSING_INVOICE";
    public static final String PRICE_NON_COVERING_INTERVAL_IGNORED = "PRICE_NON_COVERING_INTERVAL_IGNORED";
    public static final String PRICE_OVERLAPPING_INTERVALS_CONFLICT = "PRICE_OVERLAPPING_INTERVALS_CONFLICT";
    public static final String PRICE_NO_COVERING_INTERVAL = "PRICE_NO_COVERING_INTERVAL";

    public static final String PROMOTION_ACCRUAL_EXCEEDS_SETTLEMENT = "PROMOTION_ACCRUAL_EXCEEDS_SETTLEMENT";
    public static final String PROMOTION_SETTLEMENT_MATCHES = "PROMOTION_SETTLEMENT_MATCHES";
    public static final String PROMOTION_MISSING_SETTLEMENT = "PROMOTION_MISSING_SETTLEMENT";
    public static final String PROMOTION_CLAUSE_OVERRIDES_SUMMARY = "PROMOTION_CLAUSE_OVERRIDES_SUMMARY";
    public static final String PROMOTION_SAME_LEVEL_CLAUSES_DISAGREE =
            "PROMOTION_SAME_LEVEL_CLAUSES_DISAGREE";
    public static final String PROMOTION_NO_AGREEMENT = "PROMOTION_NO_AGREEMENT";

    public Oracle() {
        PolicyProvider provider = new PolicyProvider();
        frozen(provider.text(), provider.version());
    }

    static void frozen(String policyText, String policyVersion) {
        if (!POLICY_SHA256.equals(PolicyProvider.sha256(policyText))) {
            throw new IllegalStateException("the audit policy no longer matches the oracle's frozen sha256");
        }
        if (!POLICY_VERSION.equals(policyVersion)) {
            throw new IllegalStateException("the audit policy version no longer matches the oracle's frozen prefix");
        }
    }

    public OracleExpectation expectation(CorpusCase facts) {
        return switch (facts.matrix().category()) {
            case SHORTAGE -> shortage(facts);
            case PRICE_VARIANCE -> price(facts);
            case PROMOTION -> promotion(facts);
        };
    }

    private OracleExpectation shortage(CorpusCase facts) {
        if (facts.bol() == null) {
            return inconclusive(ParkedReason.EVIDENCE_GAP, SHORTAGE_MISSING_BOL);
        }
        if (facts.pod() == null) {
            return inconclusive(ParkedReason.EVIDENCE_GAP, SHORTAGE_MISSING_POD);
        }
        int shipped = facts.bol().shippedQty();
        int received = facts.pod().receivedQty();
        if (received > shipped) {
            return inconclusive(ParkedReason.POLICY_GAP, SHORTAGE_POD_ABOVE_BOL);
        }
        boolean asnDisagrees = facts.asn() != null && facts.asn().declaredQty() != shipped;
        String branch = asnDisagrees ? SHORTAGE_ASN_BOL_DISAGREE_BOL_WINS
                : received == shipped ? SHORTAGE_POD_EQUALS_BOL : SHORTAGE_POD_BELOW_BOL;
        return new OracleExpectation(received == shipped ? VerdictValue.DEDUCTION_STANDS
                : VerdictValue.DISPUTABLE, null,
                List.of(EvidenceIds.bol(facts.shipmentId()), EvidenceIds.pod(facts.shipmentId())), branch);
    }

    private OracleExpectation price(CorpusCase facts) {
        if (facts.invoiceLine() == null) {
            return inconclusive(ParkedReason.EVIDENCE_GAP, PRICE_MISSING_INVOICE);
        }
        List<PriceInterval> covering = covering(facts);
        if (covering.isEmpty()) {
            return inconclusive(ParkedReason.POLICY_GAP, PRICE_NO_COVERING_INTERVAL);
        }
        List<BigDecimal> prices = new ArrayList<>();
        for (PriceInterval interval : covering) {
            if (!prices.contains(interval.unitPrice())) {
                prices.add(interval.unitPrice());
            }
        }
        if (prices.size() > 1) {
            return inconclusive(ParkedReason.POLICY_GAP, PRICE_OVERLAPPING_INTERVALS_CONFLICT);
        }
        PriceInterval effective = covering.getFirst();
        boolean below = facts.invoiceLine().unitPrice().compareTo(effective.unitPrice()) < 0;
        String branch;
        if (facts.priceIntervals().size() > covering.size()) {
            branch = PRICE_NON_COVERING_INTERVAL_IGNORED;
        } else {
            branch = below ? PRICE_INVOICE_BELOW_MASTER : PRICE_INVOICE_AT_OR_ABOVE_MASTER;
        }
        return new OracleExpectation(below ? VerdictValue.DISPUTABLE : VerdictValue.DEDUCTION_STANDS,
                null, List.of("PRICE:" + facts.sku() + ":@" + effective.validFromDaysAgo(),
                EvidenceIds.invoice(facts.invoiceNo())), branch);
    }

    private static List<PriceInterval> covering(CorpusCase facts) {
        int date = facts.deductionDaysAgo();
        return facts.priceIntervals().stream()
                .filter(interval -> interval.validFromDaysAgo() >= date)
                .filter(interval -> interval.validToDaysAgo() == null || date >= interval.validToDaysAgo())
                .toList();
    }

    private OracleExpectation promotion(CorpusCase facts) {
        if (facts.settlement() == null) {
            return inconclusive(ParkedReason.EVIDENCE_GAP, PROMOTION_MISSING_SETTLEMENT);
        }
        if (facts.agreements().isEmpty()) {
            return inconclusive(ParkedReason.POLICY_GAP, PROMOTION_NO_AGREEMENT);
        }
        boolean overrides = false;
        boolean supports = false;
        boolean denies = false;
        for (CorpusCase.Agreement agreement : facts.agreements()) {
            for (CorpusCase.Clause clause : agreement.clauses()) {
                ClauseEffect effect = clause.effect();
                overrides = overrides || effect == ClauseEffect.CONFLICTS_WITH_SUMMARY;
                supports = supports || effect == ClauseEffect.SUPPORTS_ACCRUAL;
                denies = denies || effect == ClauseEffect.DENIES_ACCRUAL;
            }
        }
        if (!overrides && supports && denies) {
            return inconclusive(ParkedReason.POLICY_GAP, PROMOTION_SAME_LEVEL_CLAUSES_DISAGREE);
        }
        BigDecimal accrued = facts.settlement().accruedAmount();
        BigDecimal settled = facts.settlement().settledAmount();
        if (supports && accrued.compareTo(settled) > 0) {
            return new OracleExpectation(VerdictValue.DISPUTABLE, null,
                    List.of(accrualClause(facts), EvidenceIds.settlement(facts.settlement().docId())),
                    overrides ? PROMOTION_CLAUSE_OVERRIDES_SUMMARY : PROMOTION_ACCRUAL_EXCEEDS_SETTLEMENT);
        }
        return new OracleExpectation(VerdictValue.DEDUCTION_STANDS, null,
                List.of(EvidenceIds.settlement(facts.settlement().docId())),
                overrides ? PROMOTION_CLAUSE_OVERRIDES_SUMMARY : PROMOTION_SETTLEMENT_MATCHES);
    }

    private static String accrualClause(CorpusCase facts) {
        for (CorpusCase.Agreement agreement : facts.agreements()) {
            for (CorpusCase.Clause clause : agreement.clauses()) {
                if (clause.effect() == ClauseEffect.SUPPORTS_ACCRUAL) {
                    return EvidenceIds.promotion(agreement.docId(), clause.clauseNo());
                }
            }
        }
        throw new IllegalStateException("no accrual clause exists for " + facts.caseId());
    }

    private static OracleExpectation inconclusive(ParkedReason reason, String branch) {
        return new OracleExpectation(VerdictValue.INCONCLUSIVE, reason, List.of(), branch);
    }
}
