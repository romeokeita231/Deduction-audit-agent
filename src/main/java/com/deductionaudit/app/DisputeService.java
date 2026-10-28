package com.deductionaudit.app;

import com.deductionaudit.agent.DraftingAgent;
import com.deductionaudit.agent.EvidenceDigestBuilder;
import com.deductionaudit.domain.Claim;
import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.DisputeDocument;
import com.deductionaudit.domain.VerdictRecord;
import com.deductionaudit.persistence.DeductionStore;
import com.deductionaudit.shared.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class DisputeService {

    private final DeductionStore store;
    private final ActionService actions;
    private final EvidenceDigestBuilder digestBuilder;
    private final DraftingAgent draftingAgent;
    private final ObjectMapper objectMapper;

    public DisputeService(DeductionStore store, ActionService actions, EvidenceDigestBuilder digestBuilder,
                          DraftingAgent draftingAgent, ObjectMapper objectMapper) {
        this.store = store;
        this.actions = actions;
        this.digestBuilder = digestBuilder;
        this.draftingAgent = draftingAgent;
        this.objectMapper = objectMapper;
    }

    public record Disputed(ActionOutcome outcome, DocumentView document) {
    }

    public Disputed dispute(UUID id, String submittedBy, String note) {
        Deduction deduction = store.find(id)
                .orElseThrow(() -> ApiException.notFound("deduction not found"));
        VerdictRecord verdict = actions.requireDisputable(deduction);
        Claim claim = Claim.from(deduction, verdict);
        int evidenceVersion = store.evidenceVersion().version();
        List<EvidenceDigestBuilder.DigestItem> digest = digestBuilder.build(deduction, claim);
        DraftingAgent.DraftResult draft = draftingAgent.draft(claim, digest);

        DisputeDocument document = new DisputeDocument(claim, draft.narrative(),
                objectMapper.valueToTree(digest), evidenceVersion,
                draft.promptVersion(), draft.model());
        ActionOutcome outcome = actions.dispute(id, submittedBy, note, document);

        DocumentView view = store.findDisputeDocument(id)
                .map(row -> DocumentView.from(row, objectMapper))
                .orElseThrow(() -> ApiException.internal("DRAFT_INCONSISTENT",
                        "the dispute document was not written"));
        return new Disputed(outcome, view);
    }
}
