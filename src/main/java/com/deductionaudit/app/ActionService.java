package com.deductionaudit.app;

import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.DeductionStatus;
import com.deductionaudit.domain.TransitionContext;
import com.deductionaudit.domain.TransitionEvent;
import com.deductionaudit.domain.VerdictRecord;
import com.deductionaudit.domain.VerdictValue;
import com.deductionaudit.persistence.DeductionStore;
import com.deductionaudit.web.ApiException;
import com.deductionaudit.web.WindowExpiredException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ActionService {

    private final DeductionStore store;
    private final WindowService windows;
    private final Clock clock;
    private final TransitionApplier transitions;

    public ActionService(DeductionStore store, WindowService windows, Clock clock,
                         TransitionApplier transitions) {
        this.store = store;
        this.windows = windows;
        this.clock = clock;
        this.transitions = transitions;
    }

    @Transactional(noRollbackFor = WindowExpiredException.class)
    public ActionOutcome dispute(UUID id, String submittedBy, String note) {
        Deduction deduction = load(id);
        VerdictRecord verdict = requireAwaitingAction(deduction);
        if (verdict.verdict() == VerdictValue.INCONCLUSIVE) {
            throw ApiException.conflict("INCONCLUSIVE_CANNOT_DISPUTE",
                    "an inconclusive verdict can never be disputed");
        }
        store.insertDispute(id, clock.instant(), submittedBy, note);
        Deduction disputed = transitions.apply(deduction, TransitionEvent.DISPUTE,
                TransitionContext.forVerdict(verdict.verdict(), verdict.missingEvidence()), null);
        var dispute = store.findDispute(id).orElseThrow();
        return new ActionOutcome(disputed.id(), disputed.status(), dispute.id().toString(),
                dispute.openedAt(), dispute.closedAt(), dispute.result());
    }

    @Transactional(noRollbackFor = WindowExpiredException.class)
    public ActionOutcome close(UUID id, String outcome) {
        Deduction deduction = load(id);
        if ("ACCEPTED".equals(outcome)) {
            VerdictRecord verdict = requireAwaitingAction(deduction);
            Deduction accepted = transitions.apply(deduction, TransitionEvent.ACCEPT,
                    TransitionContext.forVerdict(verdict.verdict(), verdict.missingEvidence()), null);
            return new ActionOutcome(accepted.id(), accepted.status(), null, null, null, null);
        }
        if ("WRITTEN_OFF".equals(outcome)) {
            if (deduction.status() != DeductionStatus.AWAITING_ACTION
                    && deduction.status() != DeductionStatus.PARKED) {
                throw ApiException.conflict("ILLEGAL_TRANSITION",
                        "write-off needs AWAITING_ACTION or PARKED, not " + deduction.status());
            }
            Deduction writtenOff = transitions.apply(deduction, TransitionEvent.WRITE_OFF,
                    TransitionContext.empty(), null);
            return new ActionOutcome(writtenOff.id(), writtenOff.status(), null, null, null, null);
        }
        throw ApiException.badRequest("INVALID_OUTCOME", "outcome must be ACCEPTED or WRITTEN_OFF");
    }

    @Transactional
    public ActionOutcome recordResult(UUID id, String result, Instant closedAt) {
        if (closedAt == null) {
            throw ApiException.badRequest("RESULT_INCOMPLETE", "closedAt is required");
        }
        Deduction deduction = load(id);
        if (deduction.status() != DeductionStatus.DISPUTED) {
            throw ApiException.conflict("ILLEGAL_TRANSITION",
                    "results can only be recorded for a disputed deduction");
        }
        var dispute = store.findDispute(id)
                .orElseThrow(() -> ApiException.conflict("NO_DISPUTE", "a dispute record is required"));
        store.closeDispute(id, closedAt, result);
        Deduction resolved = transitions.apply(deduction, TransitionEvent.RECORD_RESULT,
                new TransitionContext(false, null, List.of(), 0, Duration.ZERO, true, true, result), null);
        return new ActionOutcome(resolved.id(), resolved.status(), dispute.id().toString(),
                dispute.openedAt(), closedAt, result);
    }

    private Deduction load(UUID id) {
        store.lockForUpdate(id);
        Deduction deduction = store.find(id).orElseThrow(() -> ApiException.notFound("deduction not found"));
        return expireIfNeeded(deduction);
    }

    private Deduction expireIfNeeded(Deduction deduction) {
        if (deduction.status() == DeductionStatus.DISPUTED || deduction.status().isTerminal()) {
            return deduction;
        }
        if (windows.isExpired(deduction, windows.timeZoneOf(deduction))) {
            transitions.apply(deduction, TransitionEvent.EXPIRE, TransitionContext.atWindow(true), null);
            throw new WindowExpiredException();
        }
        return deduction;
    }

    private VerdictRecord requireAwaitingAction(Deduction deduction) {
        if (deduction.status() != DeductionStatus.AWAITING_ACTION) {
            throw ApiException.conflict("ILLEGAL_TRANSITION",
                    "the deduction is " + deduction.status() + ", not AWAITING_ACTION");
        }
        return store.latestVerdict(deduction.id())
                .orElseThrow(() -> ApiException.conflict("NO_VERDICT", "no verdict has been recorded"));
    }
}
