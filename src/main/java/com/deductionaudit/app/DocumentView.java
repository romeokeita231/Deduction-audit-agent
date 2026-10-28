package com.deductionaudit.app;

import com.deductionaudit.domain.Claim;
import com.deductionaudit.persistence.DeductionStore;
import com.deductionaudit.shared.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;

public record DocumentView(
        int attemptNo,
        Claim claim,
        String narrative,
        Object digest,
        int evidenceVersion,
        String promptVersion,
        String model,
        Instant draftedAt) {

    // Jackson 3 renders the MVC response, so the Jackson 2 tree is flattened to plain Java values.
    static DocumentView from(DeductionStore.DisputeDocumentRow row, ObjectMapper mapper) {
        try {
            return new DocumentView(row.attemptNo(), mapper.treeToValue(row.claim(), Claim.class),
                    row.narrative(), mapper.convertValue(row.digest(), Object.class),
                    row.evidenceVersion(), row.promptVersion(), row.model(), row.draftedAt());
        } catch (Exception e) {
            throw ApiException.internal("DRAFT_INCONSISTENT", "the stored claim cannot be read");
        }
    }
}
