package com.deductionaudit.app;

import com.deductionaudit.agent.PolicyProvider;
import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.ParkedReason;
import com.deductionaudit.domain.StateMachine;
import com.deductionaudit.domain.TransitionContext;
import com.deductionaudit.domain.TransitionEvent;
import com.deductionaudit.domain.TransitionOutcome;
import com.deductionaudit.persistence.DeductionStore;
import com.deductionaudit.web.ApiException;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class TransitionApplier {

    private final DeductionStore store;
    private final Clock clock;
    private final PolicyProvider policy;

    public TransitionApplier(DeductionStore store, Clock clock, PolicyProvider policy) {
        this.store = store;
        this.clock = clock;
        this.policy = policy;
    }

    public Deduction apply(Deduction deduction, TransitionEvent event, TransitionContext context,
                           Integer attemptNo) {
        TransitionOutcome outcome = StateMachine.transition(deduction.status(), event, context);
        if (outcome instanceof TransitionOutcome.Rejected rejected) {
            throw ApiException.conflict(rejected.code(), rejected.message());
        }
        TransitionOutcome.Allowed allowed = (TransitionOutcome.Allowed) outcome;
        String parkedPolicyVersion = allowed.parkedReason() == ParkedReason.POLICY_GAP
                ? policy.version() : null;
        boolean updated = store.updateStatus(deduction.id(), deduction.version(), deduction.status(),
                allowed.target(), allowed.parkedReason(), parkedPolicyVersion);
        if (!updated) {
            throw ApiException.conflict("CONCURRENT_MODIFICATION",
                    "the deduction was changed by another request");
        }
        store.appendTransition(deduction.id(), deduction.status(), allowed.target(), event.name(),
                attemptNo, clock.instant());
        return deduction.withStatus(allowed.target(), allowed.parkedReason(), parkedPolicyVersion,
                deduction.version() + 1);
    }
}
