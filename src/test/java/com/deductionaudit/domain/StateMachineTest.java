package com.deductionaudit.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StateMachineTest {

    private static TransitionOutcome transition(DeductionStatus from, TransitionEvent event,
                                                TransitionContext context) {
        return StateMachine.transition(from, event, context);
    }

    @Test
    void terminalStatesRejectEveryEvent() {
        for (DeductionStatus terminal : List.of(DeductionStatus.ACCEPTED, DeductionStatus.WRITTEN_OFF,
                DeductionStatus.EXPIRED, DeductionStatus.WON, DeductionStatus.LOST)) {
            for (TransitionEvent event : TransitionEvent.values()) {
                TransitionOutcome outcome = transition(terminal, event, TransitionContext.empty());
                assertThat(outcome).isInstanceOf(TransitionOutcome.Rejected.class);
                assertThat(((TransitionOutcome.Rejected) outcome).code()).isEqualTo("TERMINAL_STATE");
            }
        }
    }

    @Test
    void expiredWindowBlocksTransitionsButAllowsExpiry() {
        TransitionOutcome blocked = transition(DeductionStatus.NEW, TransitionEvent.START_EVALUATION,
                TransitionContext.atWindow(true));
        assertThat(blocked).isEqualTo(new TransitionOutcome.Rejected("WINDOW_EXPIRED",
                "dispute window has passed"));

        TransitionOutcome expired = transition(DeductionStatus.AWAITING_ACTION, TransitionEvent.EXPIRE,
                TransitionContext.atWindow(true));
        assertThat(expired).isEqualTo(TransitionOutcome.allowed(DeductionStatus.EXPIRED));
        assertThat(transition(DeductionStatus.NEW, TransitionEvent.EXPIRE, TransitionContext.empty()))
                .isInstanceOf(TransitionOutcome.Rejected.class);
    }

    @Test
    void retryNeedsOnePriorAttemptAndMoreThanTwelveSeconds() {
        assertThat(transition(DeductionStatus.EVALUATING, TransitionEvent.RETRY_EVALUATION,
                new TransitionContext(false, null, List.of(), 0, Duration.ofSeconds(13), false, false, null)))
                .isEqualTo(TransitionOutcome.allowed(DeductionStatus.NEW));
        assertThat(transition(DeductionStatus.EVALUATING, TransitionEvent.RETRY_EVALUATION,
                new TransitionContext(false, null, List.of(), 0, Duration.ofSeconds(12), false, false, null)))
                .isInstanceOf(TransitionOutcome.Rejected.class);
        assertThat(transition(DeductionStatus.EVALUATING, TransitionEvent.RETRY_EVALUATION,
                new TransitionContext(false, null, List.of(), 1, Duration.ofSeconds(40), false, false, null)))
                .isInstanceOf(TransitionOutcome.Rejected.class);
    }

    @Test
    void awaitingActionNeedsAVerdict() {
        assertThat(transition(DeductionStatus.EVALUATING, TransitionEvent.VERDICT_RECORDED,
                TransitionContext.empty())).isInstanceOf(TransitionOutcome.Rejected.class);
        assertThat(transition(DeductionStatus.EVALUATING, TransitionEvent.VERDICT_RECORDED,
                TransitionContext.forVerdict(VerdictValue.DISPUTABLE, List.of())))
                .isEqualTo(TransitionOutcome.allowed(DeductionStatus.AWAITING_ACTION));
    }

    @Test
    void disputeIsOnlyForDisputable() {
        assertThat(transition(DeductionStatus.AWAITING_ACTION, TransitionEvent.DISPUTE,
                TransitionContext.forVerdict(VerdictValue.DISPUTABLE, List.of())))
                .isEqualTo(TransitionOutcome.allowed(DeductionStatus.DISPUTED));
        assertThat(transition(DeductionStatus.AWAITING_ACTION, TransitionEvent.DISPUTE,
                TransitionContext.forVerdict(VerdictValue.DEDUCTION_STANDS, List.of())))
                .isEqualTo(new TransitionOutcome.Rejected("VERDICT_MISMATCH",
                        "DISPUTE is not allowed for verdict DEDUCTION_STANDS"));
        assertThat(transition(DeductionStatus.AWAITING_ACTION, TransitionEvent.DISPUTE,
                TransitionContext.forVerdict(VerdictValue.INCONCLUSIVE, List.of())))
                .isEqualTo(new TransitionOutcome.Rejected("INCONCLUSIVE_CANNOT_DISPUTE",
                        "an inconclusive verdict can never be disputed"));
        assertThat(transition(DeductionStatus.PARKED, TransitionEvent.DISPUTE,
                TransitionContext.forVerdict(VerdictValue.DISPUTABLE, List.of())))
                .isInstanceOf(TransitionOutcome.Rejected.class);
    }

    @Test
    void acceptOnlyTakesStands() {
        assertThat(transition(DeductionStatus.AWAITING_ACTION, TransitionEvent.ACCEPT,
                TransitionContext.forVerdict(VerdictValue.DEDUCTION_STANDS, List.of())))
                .isEqualTo(TransitionOutcome.allowed(DeductionStatus.ACCEPTED));
        assertThat(transition(DeductionStatus.AWAITING_ACTION, TransitionEvent.ACCEPT,
                TransitionContext.forVerdict(VerdictValue.DISPUTABLE, List.of())))
                .isInstanceOf(TransitionOutcome.Rejected.class);
    }

    @Test
    void parkedReasonSplitsOnMissingEvidence() {
        assertThat(transition(DeductionStatus.AWAITING_ACTION, TransitionEvent.PARK_FOR_HUMAN,
                TransitionContext.forVerdict(VerdictValue.INCONCLUSIVE, List.of(MissingEvidence.POD))))
                .isEqualTo(TransitionOutcome.allowed(DeductionStatus.PARKED, ParkedReason.EVIDENCE_GAP));
        assertThat(transition(DeductionStatus.AWAITING_ACTION, TransitionEvent.PARK_FOR_HUMAN,
                TransitionContext.forVerdict(VerdictValue.INCONCLUSIVE, List.of())))
                .isEqualTo(TransitionOutcome.allowed(DeductionStatus.PARKED, ParkedReason.POLICY_GAP));
    }

    @Test
    void resultsNeedDisputeRecordAndClosedAtAndAValidResult() {
        assertThat(transition(DeductionStatus.DISPUTED, TransitionEvent.RECORD_RESULT,
                new TransitionContext(false, null, List.of(), 0, Duration.ZERO, false, true, "WON")))
                .isEqualTo(new TransitionOutcome.Rejected("NO_DISPUTE", "a dispute record is required"));
        assertThat(transition(DeductionStatus.DISPUTED, TransitionEvent.RECORD_RESULT,
                new TransitionContext(false, null, List.of(), 0, Duration.ZERO, true, false, "WON")))
                .isEqualTo(new TransitionOutcome.Rejected("RESULT_INCOMPLETE", "closedAt is required"));
        assertThat(transition(DeductionStatus.DISPUTED, TransitionEvent.RECORD_RESULT,
                new TransitionContext(false, null, List.of(), 0, Duration.ZERO, true, true, "MAYBE")))
                .isEqualTo(new TransitionOutcome.Rejected("INVALID_RESULT", "result must be WON or LOST"));
        assertThat(transition(DeductionStatus.DISPUTED, TransitionEvent.RECORD_RESULT,
                new TransitionContext(false, null, List.of(), 0, Duration.ZERO, true, true, "LOST")))
                .isEqualTo(TransitionOutcome.allowed(DeductionStatus.LOST));
    }

    @Test
    void requeueIsOnlyFromParked() {
        assertThat(transition(DeductionStatus.PARKED, TransitionEvent.REQUEUE, TransitionContext.empty()))
                .isEqualTo(TransitionOutcome.allowed(DeductionStatus.NEW));
        assertThat(transition(DeductionStatus.NEW, TransitionEvent.REQUEUE, TransitionContext.empty()))
                .isInstanceOf(TransitionOutcome.Rejected.class);
    }

    @Test
    void writeOffWorksFromAwaitingActionAndParked() {
        assertThat(transition(DeductionStatus.PARKED, TransitionEvent.WRITE_OFF, TransitionContext.empty()))
                .isEqualTo(TransitionOutcome.allowed(DeductionStatus.WRITTEN_OFF));
        assertThat(transition(DeductionStatus.AWAITING_ACTION, TransitionEvent.WRITE_OFF,
                TransitionContext.empty()))
                .isEqualTo(TransitionOutcome.allowed(DeductionStatus.WRITTEN_OFF));
        assertThat(transition(DeductionStatus.NEW, TransitionEvent.WRITE_OFF, TransitionContext.empty()))
                .isInstanceOf(TransitionOutcome.Rejected.class);
    }
}
