package com.deductionaudit.agent;

import com.deductionaudit.domain.Claim;
import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.EvidenceReference;
import com.deductionaudit.persistence.EvidenceStore;
import com.deductionaudit.shared.ApiException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class EvidenceDigestBuilder {

    private static final int CLAUSE_TEXT_LIMIT = 500;

    private final EvidenceStore evidence;

    public EvidenceDigestBuilder(EvidenceStore evidence) {
        this.evidence = evidence;
    }

    public record DigestItem(String evidenceId, String kind, Map<String, Object> fields) {
    }

    public List<DigestItem> build(Deduction deduction, Claim claim) {
        List<DigestItem> items = new ArrayList<>();
        for (String id : claim.closedSet()) {
            items.add(resolve(deduction, id));
        }
        items.sort(Comparator.comparing(DigestItem::evidenceId));
        return List.copyOf(items);
    }

    private DigestItem resolve(Deduction deduction, String id) {
        try {
            EvidenceReference reference = EvidenceReference.parse(id);
            Map<String, Object> fields = new LinkedHashMap<>();
            switch (reference.kind()) {
                case ASN -> {
                    EvidenceStore.AsnRow row = evidence.asn(reference.key())
                            .orElseThrow(() -> unresolved(id));
                    put(fields, "declaredQty", row.declaredQty());
                    put(fields, "shippedAt", iso(row.shippedAt()));
                }
                case BOL -> {
                    EvidenceStore.BolRow row = evidence.bol(reference.key())
                            .orElseThrow(() -> unresolved(id));
                    put(fields, "shippedQty", row.shippedQty());
                    put(fields, "shippedAt", iso(row.shippedAt()));
                }
                case POD -> {
                    EvidenceStore.PodRow row = evidence.pod(reference.key())
                            .orElseThrow(() -> unresolved(id));
                    put(fields, "receivedQty", row.receivedQty());
                    put(fields, "receivedAt", iso(row.receivedAt()));
                }
                case PRICE -> {
                    EvidenceStore.PriceRow row = evidence.priceAt(reference.key(),
                                    LocalDate.parse(reference.qualifier()))
                            .orElseThrow(() -> unresolved(id));
                    put(fields, "unitPrice", money(row.unitPrice()));
                    put(fields, "validFrom", iso(row.validFrom()));
                    put(fields, "validTo", iso(row.validTo()));
                }
                case INVOICE -> {
                    EvidenceStore.InvoiceRow row = evidence.invoiceLine(reference.key())
                            .orElseThrow(() -> unresolved(id));
                    put(fields, "qty", row.qty());
                    put(fields, "unitPrice", money(row.unitPrice()));
                }
                case PROMO -> {
                    EvidenceStore.ClauseRow row = evidence.clauses(reference.key()).stream()
                            .filter(clause -> clause.clauseNo().equals(reference.qualifier()))
                            .findFirst()
                            .orElseThrow(() -> unresolved(id));
                    put(fields, "clauseNo", row.clauseNo());
                    put(fields, "text", truncate(row.text()));
                }
                case SETTLE -> {
                    EvidenceStore.SettlementRow row = evidence.settlement(reference.key())
                            .orElseThrow(() -> unresolved(id));
                    put(fields, "accruedAmount", money(row.accruedAmount()));
                    put(fields, "settledAmount", money(row.settledAmount()));
                    put(fields, "settledOn", iso(row.settledOn()));
                }
                case EDI812 -> put(fields, "deductionId", deduction.id().toString());
            }
            return new DigestItem(id, reference.kind().name(),
                    Collections.unmodifiableMap(new LinkedHashMap<>(fields)));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw ApiException.internal("DRAFT_INCONSISTENT", "cannot resolve evidence id " + id);
        }
    }

    private static void put(Map<String, Object> fields, String name, Object value) {
        if (value != null) {
            fields.put(name, value);
        }
    }

    private static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String iso(Instant value) {
        return value == null ? null : value.toString();
    }

    private static String iso(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private static String truncate(String text) {
        return text != null && text.length() > CLAUSE_TEXT_LIMIT
                ? text.substring(0, CLAUSE_TEXT_LIMIT) + "..." : text;
    }

    private static ApiException unresolved(String id) {
        return ApiException.internal("DRAFT_INCONSISTENT", "cannot resolve evidence id " + id);
    }
}
