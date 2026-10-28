package com.deductionaudit;

import com.deductionaudit.domain.MissingEvidence;
import com.deductionaudit.domain.VerdictRecord;
import com.deductionaudit.domain.VerdictValue;
import com.deductionaudit.persistence.DeductionStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Containers are restarted per test class, so the cached context must not outlive this one.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DisputeDocumentStoreIntegrationTest extends IntegrationTestBase {

    private static final UUID DISPUTE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STANDS = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private DeductionStore store;

    @Test
    void aDocumentCannotHangOffAnotherDeductionsDispute() {
        insertVerdict(DISPUTE, 1);
        insertVerdict(STANDS, 1);
        store.insertDispute(DISPUTE, FIXED_NOW, "ar-1", null);
        UUID disputeId = store.findDispute(DISPUTE).orElseThrow().id();

        JsonNode claim = claim(DISPUTE);
        JsonNode digest = json.createArrayNode();

        assertThatThrownBy(() -> store.insertDisputeDocument(disputeId, STANDS, 1, claim, "narrative",
                digest, 1, "sha256:prompt", "replay", FIXED_NOW))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(store.findDisputeDocument(STANDS)).isEmpty();
        assertThat(store.findDisputeDocument(DISPUTE)).isEmpty();
    }

    @Test
    void aSecondDocumentForTheSameDisputeIsRejectedByThePrimaryKey() {
        insertVerdict(DISPUTE, 1);
        store.insertDispute(DISPUTE, FIXED_NOW, "ar-1", null);
        UUID disputeId = store.findDispute(DISPUTE).orElseThrow().id();
        JsonNode claim = claim(DISPUTE);
        JsonNode digest = json.createArrayNode();

        store.insertDisputeDocument(disputeId, DISPUTE, 1, claim, "narrative", digest, 1,
                "sha256:prompt", "replay", FIXED_NOW);

        assertThatThrownBy(() -> store.insertDisputeDocument(disputeId, DISPUTE, 1, claim, "narrative",
                digest, 1, "sha256:prompt", "replay", FIXED_NOW))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM dispute_document WHERE dispute_id = ?::uuid",
                Integer.class, disputeId)).isEqualTo(1);
    }

    @Test
    void claimAndDigestRoundTripThroughJsonb() throws Exception {
        insertVerdict(DISPUTE, 1);
        store.insertDispute(DISPUTE, FIXED_NOW, "ar-1", null);
        UUID disputeId = store.findDispute(DISPUTE).orElseThrow().id();
        JsonNode claim = json.readTree("""
                {"deductionId":"11111111-1111-1111-1111-111111111111","attemptNo":1,
                 "amount":"480.00","ask":{"type":"REVERSE_DEDUCTION"},
                 "references":["EDI812:11111111-1111-1111-1111-111111111111"],
                 "supportingEvidence":["BOL:SHP-DISPUTE","POD:SHP-DISPUTE"],"basis":[]}
                """);
        JsonNode digest = json.readTree("""
                [{"evidenceId":"BOL:SHP-DISPUTE","kind":"BOL","fields":{"shippedQty":120,"carrier":"XPO"}},
                 {"evidenceId":"EDI812:11111111-1111-1111-1111-111111111111","kind":"EDI812",
                  "fields":{"deductionId":"11111111-1111-1111-1111-111111111111"}}]
                """);

        store.insertDisputeDocument(disputeId, DISPUTE, 1, claim, "The deduction is not supported.",
                digest, 1, "sha256:prompt", "replay", FIXED_NOW);

        DeductionStore.DisputeDocumentRow row = store.findDisputeDocument(DISPUTE).orElseThrow();
        assertThat(row.disputeId()).isEqualTo(disputeId);
        assertThat(row.attemptNo()).isEqualTo(1);
        assertThat(row.claim()).isEqualTo(claim);
        assertThat(row.digest()).isEqualTo(digest);
        assertThat(row.narrative()).isEqualTo("The deduction is not supported.");
        assertThat(row.evidenceVersion()).isEqualTo(1);
        assertThat(row.promptVersion()).isEqualTo("sha256:prompt");
        assertThat(row.model()).isEqualTo("replay");
        assertThat(row.draftedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void theEvidenceVersionIsReadableAfterReseeding() {
        DeductionStore.EvidenceVersion version = store.evidenceVersion();
        assertThat(version.version()).isEqualTo(1);
        assertThat(version.updatedAt()).isNotNull();
    }

    private void insertVerdict(UUID deductionId, int attemptNo) {
        store.insertVerdict(new VerdictRecord(deductionId, attemptNo, VerdictValue.DISPUTABLE,
                List.<MissingEvidence>of(), List.of("EDI812:" + deductionId), List.of("BOL:SHP-DISPUTE"),
                "reasoning", "sha256:prompt", "policy", 1, "replay", FIXED_NOW));
    }

    private JsonNode claim(UUID deductionId) {
        return json.createObjectNode().put("deductionId", deductionId.toString());
    }
}
