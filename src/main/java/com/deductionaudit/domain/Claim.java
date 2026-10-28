package com.deductionaudit.domain;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

public record Claim(
        UUID deductionId,
        int attemptNo,
        String category,
        String amount,
        String currency,
        Ask ask,
        List<String> references,
        List<String> supportingEvidence,
        List<String> basis) {

    public static Claim from(Deduction deduction, VerdictRecord verdict) {
        List<String> references = new ArrayList<>();
        references.add(EvidenceIds.edi812(deduction.id()));
        addUnique(references, verdict.references());

        List<String> supporting = new ArrayList<>();
        addUnique(supporting, verdict.supportingEvidence());

        List<String> basis = new ArrayList<>();
        addUnique(basis, supporting);
        basis.removeIf(id -> !id.startsWith("PROMO:"));

        return new Claim(
                deduction.id(),
                verdict.attemptNo(),
                deduction.category() == null ? null : deduction.category().name(),
                deduction.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                deduction.currency(),
                Ask.REVERSE_DEDUCTION,
                List.copyOf(references),
                List.copyOf(supporting),
                List.copyOf(basis));
    }

    public List<String> closedSet() {
        LinkedHashSet<String> ids = new LinkedHashSet<>(references);
        ids.addAll(supportingEvidence);
        ids.addAll(basis);
        return List.copyOf(ids);
    }

    private static void addUnique(List<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>(target);
        for (String value : values) {
            if (seen.add(value)) {
                target.add(value);
            }
        }
    }
}
