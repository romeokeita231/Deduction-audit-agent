package com.deductionaudit.app;

import com.deductionaudit.agent.EvaluationAgent;
import com.deductionaudit.agent.EvaluationAgent.InfrastructureFailure;
import com.deductionaudit.agent.EvaluationRun;
import com.deductionaudit.agent.validators.DeductionValidator;
import com.deductionaudit.agent.validators.ValidatorRegistry;
import com.deductionaudit.config.EvaluationProperties;
import com.deductionaudit.domain.AgentVerdict;
import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.DeductionCategory;
import com.deductionaudit.domain.DeductionStatus;
import com.deductionaudit.domain.ParkedReason;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class EvaluationService {

    private final DeductionStore store;
    private final WindowService windows;
    private final EvaluationAgent agent;
    private final ValidatorRegistry validators;
    private final EvaluationProperties properties;
    private final ExecutorService executor;
    private final Clock clock;
    private final TransitionApplier transitions;

    public EvaluationService(DeductionStore store, WindowService windows, EvaluationAgent agent,
                             ValidatorRegistry validators, EvaluationProperties properties,
                             ExecutorService executor, Clock clock, TransitionApplier transitions) {
        this.store = store;
        this.windows = windows;
        this.agent = agent;
        this.validators = validators;
        this.properties = properties;
        this.executor = executor;
        this.clock = clock;
        this.transitions = transitions;
    }

    @Transactional(noRollbackFor = WindowExpiredException.class)
    public EvaluationResult evaluate(UUID id, String requestedPolicyVersion) {
        store.lockForUpdate(id);
        Deduction deduction = store.find(id).orElseThrow(() -> ApiException.notFound("deduction not found"));
        deductable(deduction, windows.timeZoneOf(deduction));

        if (deduction.status() == DeductionStatus.EVALUATING) {
            throw ApiException.conflict("EVALUATING", "this deduction is already being evaluated");
        }
        if (deduction.status().isTerminal() || deduction.status() == DeductionStatus.DISPUTED) {
            throw ApiException.conflict("ILLEGAL_TRANSITION",
                    "cannot evaluate a deduction in status " + deduction.status());
        }
        if (deduction.status() == DeductionStatus.AWAITING_ACTION) {
            throw ApiException.conflict("ILLEGAL_TRANSITION", "the deduction already has a verdict");
        }
        if (deduction.status() == DeductionStatus.PARKED) {
            guardRequeue(deduction, requestedPolicyVersion);
            deduction = transitions.apply(deduction, TransitionEvent.REQUEUE, TransitionContext.empty(), null);
        }
        if (deduction.status() != DeductionStatus.NEW) {
            throw ApiException.conflict("ILLEGAL_TRANSITION",
                    "cannot evaluate a deduction in status " + deduction.status());
        }

        DeductionCategory category = store.resolveCategory(deduction.retailerId(), deduction.reasonCode())
                .orElse(null);
        if (category == null) {
            Deduction parked = transitions.apply(deduction, TransitionEvent.CATEGORIZE_UNMAPPED,
                    TransitionContext.empty(), null);
            return new EvaluationResult(parked.id(), parked.status(), parked.parkedReason(), null, null);
        }
        store.updateCategory(deduction.id(), category);
        deduction = deduction.withCategory(category);

        Instant deadline = clock.instant().plus(properties.totalBudget());
        int attemptNo = store.nextAttemptNo(deduction.id());
        deduction = transitions.apply(deduction, TransitionEvent.START_EVALUATION,
                TransitionContext.empty(), attemptNo);
        store.startAttempt(deduction.id(), attemptNo, clock.instant());

        while (true) {
            EvaluationRun run = new EvaluationRun(deduction.id(), attemptNo, clock.instant(), deadline);
            try {
                AgentVerdict verdict = callAgent(deduction, category, run, deadline);
                VerdictRecord record = persistVerdict(deduction, verdict, attemptNo);
                store.finishAttempt(deduction.id(), attemptNo, clock.instant(), "VERDICT",
                        run.toolCallCount());
                Deduction after = transitions.apply(deduction, TransitionEvent.VERDICT_RECORDED,
                        TransitionContext.forVerdict(verdict.verdict(), verdict.missingEvidence()), attemptNo);
                if (verdict.verdict() == VerdictValue.INCONCLUSIVE) {
                    after = transitions.apply(after, TransitionEvent.PARK_FOR_HUMAN,
                            new TransitionContext(false, verdict.verdict(), verdict.missingEvidence(),
                                    attemptNo - 1, Duration.ZERO, false, false, null), attemptNo);
                }
                return new EvaluationResult(after.id(), after.status(), after.parkedReason(), attemptNo, record);
            } catch (RuntimeException failure) {
                boolean timedOut = failure instanceof EvaluationTimeoutException
                        || (failure.getMessage() != null && failure.getMessage().contains("timeout"));
                store.finishAttempt(deduction.id(), attemptNo, clock.instant(),
                        timedOut ? "TIMEOUT" : "INFRA_FAILED", run.toolCallCount());
                Duration remaining = Duration.between(clock.instant(), deadline);
                boolean retryable = failure instanceof EvaluationAgent.InfrastructureFailure
                        || failure instanceof EvaluationTimeoutException;
                if (retryable
                        && attemptNo < properties.maxAttempts()
                        && remaining.compareTo(properties.retryMinRemaining()) > 0) {
                    deduction = transitions.apply(deduction, TransitionEvent.RETRY_EVALUATION,
                            new TransitionContext(false, null, List.of(), attemptNo - 1, remaining,
                                    false, false, null), attemptNo);
                    attemptNo = attemptNo + 1;
                    store.startAttempt(deduction.id(), attemptNo, clock.instant());
                    deduction = transitions.apply(deduction, TransitionEvent.START_EVALUATION,
                            TransitionContext.empty(), attemptNo);
                    continue;
                }
                Deduction parked = transitions.apply(deduction, TransitionEvent.FAIL_EVALUATION,
                        TransitionContext.empty(), attemptNo);
                return new EvaluationResult(parked.id(), parked.status(), parked.parkedReason(), attemptNo, null);
            }
        }
    }

    private void guardRequeue(Deduction deduction, String requestedPolicyVersion) {
        if (deduction.parkedReason() == ParkedReason.UNMAPPED_CODE
                && store.resolveCategory(deduction.retailerId(), deduction.reasonCode()).isEmpty()) {
            throw ApiException.conflict("UNMAPPED_CODE",
                    "the reason code is still unmapped for this retailer");
        }
        if (deduction.parkedReason() == ParkedReason.POLICY_GAP
                && (requestedPolicyVersion == null
                || requestedPolicyVersion.equals(deduction.parkedPolicyVersion()))) {
            throw ApiException.conflict("POLICY_UNCHANGED",
                    "the policy has not changed since this deduction was parked");
        }
    }

    private void deductable(Deduction deduction, String timeZone) {
        if (deduction.status() == DeductionStatus.DISPUTED || deduction.status().isTerminal()) {
            return;
        }
        if (windows.isExpired(deduction, timeZone)) {
            transitions.apply(deduction, TransitionEvent.EXPIRE, TransitionContext.atWindow(true), null);
            throw new WindowExpiredException();
        }
    }

    private AgentVerdict callAgent(Deduction deduction, DeductionCategory category, EvaluationRun run,
                                   Instant deadline) {
        DeductionValidator validator = validators.forCategory(category);
        Future<AgentVerdict> future = executor.submit(() -> agent.evaluate(deduction, validator, run));
        try {
            long remaining = Math.max(1, Duration.between(clock.instant(), deadline).toMillis());
            return future.get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            run.failInfrastructure("evaluation budget exhausted");
            throw new EvaluationTimeoutException();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new InfrastructureFailure("evaluation failed: " + cause.getMessage());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new EvaluationTimeoutException();
        }
    }

    private VerdictRecord persistVerdict(Deduction deduction, AgentVerdict verdict, int attemptNo) {
        VerdictRecord record = new VerdictRecord(
                deduction.id(),
                attemptNo,
                verdict.verdict(),
                verdict.missingEvidence(),
                verdict.references(),
                verdict.supportingEvidence(),
                verdict.reasoning(),
                agent.promptVersion(validators.forCategory(deduction.category())),
                agent.policyVersion(),
                store.mappingVersion(deduction.retailerId(), deduction.reasonCode()),
                agent.modelName(),
                clock.instant());
        store.insertVerdict(record);
        return record;
    }

    public Optional<EvaluationResult> find(UUID id) {
        return store.find(id).map(deduction -> new EvaluationResult(deduction.id(), deduction.status(),
                deduction.parkedReason(), null, store.latestVerdict(id).orElse(null)));
    }
}
