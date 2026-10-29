package com.deductionaudit.app;

import com.deductionaudit.agent.EvidenceDigestBuilder.DigestItem;
import com.deductionaudit.domain.Claim;
import com.deductionaudit.persistence.DeductionStore;
import com.deductionaudit.shared.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

public record DocumentView(
        int attemptNo,
        Claim claim,
        String narrative,
        List<DigestItem> digest,
        int evidenceVersion,
        String promptVersion,
        String model,
        Instant draftedAt) {

    static DocumentView from(DeductionStore.DisputeDocumentRow row, ObjectMapper mapper) {
        try {
            return new DocumentView(row.attemptNo(), mapper.treeToValue(row.claim(), Claim.class),
                    row.narrative(), mapper.convertValue(row.digest(), new TypeReference<List<DigestItem>>() {
                    }),
                    row.evidenceVersion(), row.promptVersion(), row.model(), row.draftedAt());
        } catch (Exception e) {
            throw ApiException.internal("DRAFT_INCONSISTENT", "the stored claim cannot be read");
        }
    }
}
