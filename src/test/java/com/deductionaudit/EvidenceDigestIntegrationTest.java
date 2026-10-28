package com.deductionaudit;

import com.deductionaudit.agent.EvidenceDigestBuilder;
import com.deductionaudit.domain.Ask;
import com.deductionaudit.domain.Claim;
import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.EvidenceIds;
import com.deductionaudit.domain.VerdictRecord;
import com.deductionaudit.persistence.DeductionStore;
import com.deductionaudit.shared.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Containers are restarted per test class, so the cached context must not outlive this one.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EvidenceDigestIntegrationTest extends IntegrationTestBase {

    private static final String SHORTAGE = "11111111-1111-1111-1111-111111111111";
    private static final String PROMOTION = "99999999-9999-9999-9999-999999999999";

    @Autowired
    private DeductionStore store;

    @Autowired
    private EvidenceDigestBuilder builder;

    @Test
    void shortageDigestCoversBolPodAndTheEdi812() {
        evaluate(SHORTAGE);
        Deduction deduction = store.find(UUID.fromString(SHORTAGE)).orElseThrow();
        VerdictRecord verdict = store.latestVerdict(deduction.id()).orElseThrow();
        Claim claim = Claim.from(deduction, verdict);

        List<EvidenceDigestBuilder.DigestItem> digest = builder.build(deduction, claim);

        assertThat(claim.basis()).isEmpty();
        assertThat(ids(digest)).containsExactly(
                "BOL:SHP-DISPUTE",
                "EDI812:" + SHORTAGE,
                "POD:SHP-DISPUTE");
        assertThat(digest).hasSize(union(claim).size());

        EvidenceDigestBuilder.DigestItem bol = item(digest, "BOL:SHP-DISPUTE");
        assertThat(bol.kind()).isEqualTo("BOL");
        assertThat(bol.fields()).containsEntry("shippedQty", 120);
        assertThat(bol.fields()).doesNotContainKey("shippedAt");

        EvidenceDigestBuilder.DigestItem pod = item(digest, "POD:SHP-DISPUTE");
        assertThat(pod.kind()).isEqualTo("POD");
        assertThat(pod.fields()).containsEntry("receivedQty", 96);
        assertThat(pod.fields()).doesNotContainKey("receivedAt");

        EvidenceDigestBuilder.DigestItem edi = item(digest, "EDI812:" + SHORTAGE);
        assertThat(edi.kind()).isEqualTo("EDI812");
        assertThat(edi.fields()).containsEntry("deductionId", SHORTAGE);

        for (EvidenceDigestBuilder.DigestItem entry : digest) {
            assertThat(entry.fields()).doesNotContainKey("resolvedAt");
        }
    }

    @Test
    void promotionDigestCoversPromoClausesSettlementAndTheEdi812() {
        evaluate(PROMOTION);
        Deduction deduction = store.find(UUID.fromString(PROMOTION)).orElseThrow();
        VerdictRecord verdict = store.latestVerdict(deduction.id()).orElseThrow();
        Claim claim = Claim.from(deduction, verdict);

        assertThat(claim.basis())
                .containsExactly("PROMO:PA-07·§3.1", "PROMO:PA-07·§3.2");

        List<EvidenceDigestBuilder.DigestItem> digest = builder.build(deduction, claim);

        assertThat(ids(digest)).containsExactly(
                "EDI812:" + PROMOTION,
                "PROMO:PA-07·§3.1",
                "PROMO:PA-07·§3.2",
                "SETTLE:PA-07");
        assertThat(digest).hasSize(union(claim).size());

        EvidenceDigestBuilder.DigestItem clause = item(digest, "PROMO:PA-07·§3.2");
        assertThat(clause.kind()).isEqualTo("PROMO");
        assertThat(clause.fields()).containsEntry("clauseNo", "3.2");
        assertThat(clause.fields().get("text").toString()).contains("30 days");

        EvidenceDigestBuilder.DigestItem settlement = item(digest, "SETTLE:PA-07");
        assertThat(settlement.kind()).isEqualTo("SETTLE");
        assertThat(settlement.fields())
                .containsEntry("accruedAmount", "640.00")
                .containsEntry("settledAmount", "400.00")
                .containsEntry("settledOn", "2026-02-20");

        for (EvidenceDigestBuilder.DigestItem entry : digest) {
            assertThat(entry.fields()).doesNotContainKey("resolvedAt");
        }
    }

    @Test
    void anUnresolvedIdFailsAsAnInternalInconsistencyNamingTheId() {
        Deduction deduction = store.find(UUID.fromString(SHORTAGE)).orElseThrow();
        Claim claim = new Claim(deduction.id(), 1, "SHORTAGE", "480.00", "USD", Ask.REVERSE_DEDUCTION,
                List.of(EvidenceIds.edi812(deduction.id()), "POD:SHP-NOPOD"), List.of(), List.of());

        assertThatThrownBy(() -> builder.build(deduction, claim))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(500);
                    assertThat(exception.code()).isEqualTo("DRAFT_INCONSISTENT");
                    assertThat(exception.getMessage()).contains("POD:SHP-NOPOD");
                });
    }

    @Test
    void theSerializedDigestKeepsTheFieldOrderAcrossBuilds() throws Exception {
        Deduction deduction = store.find(UUID.fromString(SHORTAGE)).orElseThrow();
        Claim claim = new Claim(deduction.id(), 1, "SHORTAGE", "480.00", "USD", Ask.REVERSE_DEDUCTION,
                List.of(EvidenceIds.edi812(deduction.id())),
                List.of("BOL:SHP-DISPUTE", "PRICE:SKU-1:2025-12-01", "INVOICE:INV-LOW"), List.of());

        List<EvidenceDigestBuilder.DigestItem> first = builder.build(deduction, claim);
        List<EvidenceDigestBuilder.DigestItem> second = builder.build(deduction, claim);

        ObjectMapper json = new ObjectMapper();
        assertThat(json.writeValueAsString(first)).isEqualTo(json.writeValueAsString(second));
        assertThat(new ArrayList<>(item(first, "PRICE:SKU-1:2025-12-01").fields().keySet()))
                .containsExactly("unitPrice", "validFrom", "validTo");
        assertThat(new ArrayList<>(item(first, "INVOICE:INV-LOW").fields().keySet()))
                .containsExactly("qty", "unitPrice");
    }

    @Test
    void aMalformedIdFailsAsAnInternalInconsistencyNamingTheId() {
        Deduction deduction = store.find(UUID.fromString(SHORTAGE)).orElseThrow();

        assertThatThrownBy(() -> builder.build(deduction, claim(deduction, "PRICE:SKU-1:not-a-date")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(500);
                    assertThat(exception.code()).isEqualTo("DRAFT_INCONSISTENT");
                    assertThat(exception.getMessage()).contains("PRICE:SKU-1:not-a-date");
                });

        assertThatThrownBy(() -> builder.build(deduction, claim(deduction, "PROMO:doc")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(500);
                    assertThat(exception.code()).isEqualTo("DRAFT_INCONSISTENT");
                    assertThat(exception.getMessage()).contains("PROMO:doc");
                });
    }

    private static Claim claim(Deduction deduction, String evidenceId) {
        return new Claim(deduction.id(), 1, "SHORTAGE", "480.00", "USD", Ask.REVERSE_DEDUCTION,
                List.of(evidenceId), List.of(), List.of());
    }

    private static Set<String> union(Claim claim) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.addAll(claim.references());
        ids.addAll(claim.supportingEvidence());
        ids.addAll(claim.basis());
        return ids;
    }

    private static List<String> ids(List<EvidenceDigestBuilder.DigestItem> digest) {
        return digest.stream().map(EvidenceDigestBuilder.DigestItem::evidenceId).toList();
    }

    private static EvidenceDigestBuilder.DigestItem item(List<EvidenceDigestBuilder.DigestItem> digest,
                                                         String evidenceId) {
        return digest.stream().filter(entry -> entry.evidenceId().equals(evidenceId)).findFirst()
                .orElseThrow();
    }
}
