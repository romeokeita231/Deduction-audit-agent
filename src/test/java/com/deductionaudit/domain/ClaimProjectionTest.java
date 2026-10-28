package com.deductionaudit.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimProjectionTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void amountAndCurrencyComeFromTheDeductionAndAttemptFromTheVerdict() throws Exception {
        Claim claim = Claim.from(deduction(DeductionCategory.SHORTAGE, "480.00", "USD"),
                verdict(2, List.of("EDI812:" + ID, "BOL:SHP-DISPUTE"),
                        List.of("BOL:SHP-DISPUTE", "POD:SHP-DISPUTE")));

        assertThat(claim.deductionId()).isEqualTo(ID);
        assertThat(claim.attemptNo()).isEqualTo(2);
        assertThat(claim.category()).isEqualTo("SHORTAGE");
        assertThat(claim.amount()).isEqualTo("480.00");
        assertThat(claim.currency()).isEqualTo("USD");

        JsonNode node = JSON.valueToTree(claim);
        assertThat(node.path("ask")).isEqualTo(JSON.readTree("{\"type\":\"REVERSE_DEDUCTION\"}"));
        assertThat(node.path("ask").has("amount")).isFalse();
    }

    @Test
    void referencesLeadWithTheDeductionsOwnEdi812AndAreDeduplicated() {
        Claim claim = Claim.from(deduction(DeductionCategory.SHORTAGE, "480.00", "USD"),
                verdict(1, List.of("POD:SHP-DISPUTE", "EDI812:" + ID, "POD:SHP-DISPUTE"),
                        List.of("POD:SHP-DISPUTE")));

        assertThat(claim.references()).containsExactly("EDI812:" + ID, "POD:SHP-DISPUTE");
    }

    @Test
    void supportingEvidenceKeepsTheVerdictOrderAndDropsRepeats() {
        Claim claim = Claim.from(deduction(DeductionCategory.SHORTAGE, "480.00", "USD"),
                verdict(1, List.of("EDI812:" + ID),
                        List.of("POD:SHP-DISPUTE", "BOL:SHP-DISPUTE", "POD:SHP-DISPUTE")));

        assertThat(claim.supportingEvidence()).containsExactly("POD:SHP-DISPUTE", "BOL:SHP-DISPUTE");
    }

    @Test
    void basisIsThePromoSubsetOfTheUnionAndIsNeverNull() {
        Claim shortage = Claim.from(deduction(DeductionCategory.SHORTAGE, "480.00", "USD"),
                verdict(1, List.of("EDI812:" + ID), List.of("BOL:SHP-DISPUTE", "POD:SHP-DISPUTE")));
        assertThat(shortage.basis()).isNotNull().isEmpty();

        Claim promotion = Claim.from(deduction(DeductionCategory.PROMOTION, "160.00", "USD"),
                verdict(1, List.of("EDI812:" + ID),
                        List.of("PROMO:PA-07·§3.1", "SETTLE:PA-07", "PROMO:PA-07·§3.2")));
        assertThat(promotion.basis())
                .containsExactly("PROMO:PA-07·§3.1", "PROMO:PA-07·§3.2");
        assertThat(promotion.supportingEvidence())
                .containsAll(promotion.basis());
    }

    private static Deduction deduction(DeductionCategory category, String amount, String currency) {
        return new Deduction(ID, "NYC", "SHORT", category, new BigDecimal(amount), currency,
                LocalDate.of(2026, 3, 5), Instant.parse("2026-03-05T00:00:00Z"),
                LocalDate.of(2026, 4, 4), DeductionStatus.AWAITING_ACTION, null, null, null, null,
                "SHP-DISPUTE", null, 0);
    }

    private static VerdictRecord verdict(int attemptNo, List<String> references,
                                         List<String> supporting) {
        return new VerdictRecord(ID, attemptNo, VerdictValue.DISPUTABLE, List.of(), references,
                supporting, "reasoning", "sha256:prompt", "policy", 1, "replay",
                Instant.parse("2026-03-05T00:00:00Z"));
    }
}
