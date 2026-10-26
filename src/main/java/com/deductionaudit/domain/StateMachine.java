package com.deductionaudit.domain;

public final class StateMachine {

    private StateMachine() {
    }

    public static TransitionOutcome transition(DeductionStatus from, TransitionEvent event, TransitionContext ctx) {
        if (from == null) {
            return TransitionOutcome.rejected("ILLEGAL_TRANSITION", "no current status");
        }
        if (from.isTerminal()) {
            return TransitionOutcome.rejected("TERMINAL_STATE", from + " is terminal");
        }
        boolean expirable = from == DeductionStatus.NEW
                || from == DeductionStatus.PARKED
                || from == DeductionStatus.AWAITING_ACTION;
        if (ctx.windowExpired() && expirable && event != TransitionEvent.EXPIRE) {
            return TransitionOutcome.rejected("WINDOW_EXPIRED", "dispute window has passed");
        }
        return switch (event) {
            case CATEGORIZE_UNMAPPED -> from == DeductionStatus.NEW
                    ? TransitionOutcome.allowed(DeductionStatus.PARKED, ParkedReason.UNMAPPED_CODE)
                    : illegal(from, event);
            case START_EVALUATION -> from == DeductionStatus.NEW
                    ? TransitionOutcome.allowed(DeductionStatus.EVALUATING)
                    : illegal(from, event);
            case RETRY_EVALUATION -> from == DeductionStatus.EVALUATING
                    && ctx.priorAttempts() < 1
                    && ctx.remainingBudget() != null
                    && ctx.retryMinRemaining() != null
                    && ctx.remainingBudget().compareTo(ctx.retryMinRemaining()) > 0
                    ? TransitionOutcome.allowed(DeductionStatus.NEW)
                    : TransitionOutcome.rejected("RETRY_NOT_ALLOWED",
                    "retry needs one prior attempt and more than " + ctx.retryMinRemaining()
                            + " of budget left");
            case FAIL_EVALUATION -> from == DeductionStatus.EVALUATING
                    ? TransitionOutcome.allowed(DeductionStatus.PARKED, ParkedReason.EVAL_FAILED)
                    : illegal(from, event);
            case VERDICT_RECORDED -> from == DeductionStatus.EVALUATING && ctx.verdict() != null
                    ? TransitionOutcome.allowed(DeductionStatus.AWAITING_ACTION)
                    : illegal(from, event);
            case REQUEUE -> from == DeductionStatus.PARKED
                    ? TransitionOutcome.allowed(DeductionStatus.NEW)
                    : illegal(from, event);
            case DISPUTE -> dispute(from, ctx);
            case ACCEPT -> from == DeductionStatus.AWAITING_ACTION
                    ? (ctx.verdict() == VerdictValue.DEDUCTION_STANDS
                    ? TransitionOutcome.allowed(DeductionStatus.ACCEPTED)
                    : verdictMismatch(event, ctx.verdict()))
                    : illegal(from, event);
            case WRITE_OFF -> from == DeductionStatus.AWAITING_ACTION || from == DeductionStatus.PARKED
                    ? TransitionOutcome.allowed(DeductionStatus.WRITTEN_OFF)
                    : illegal(from, event);
            case PARK_FOR_HUMAN -> from == DeductionStatus.AWAITING_ACTION
                    ? parkForHuman(ctx)
                    : illegal(from, event);
            case EXPIRE -> expirable && ctx.windowExpired()
                    ? TransitionOutcome.allowed(DeductionStatus.EXPIRED)
                    : illegal(from, event);
            case RECORD_RESULT -> recordResult(from, ctx);
        };
    }

    private static TransitionOutcome dispute(DeductionStatus from, TransitionContext ctx) {
        if (from != DeductionStatus.AWAITING_ACTION) {
            return illegal(from, TransitionEvent.DISPUTE);
        }
        if (ctx.verdict() == VerdictValue.DISPUTABLE) {
            return TransitionOutcome.allowed(DeductionStatus.DISPUTED);
        }
        if (ctx.verdict() == VerdictValue.INCONCLUSIVE) {
            return TransitionOutcome.rejected("INCONCLUSIVE_CANNOT_DISPUTE",
                    "an inconclusive verdict can never be disputed");
        }
        return verdictMismatch(TransitionEvent.DISPUTE, ctx.verdict());
    }

    private static TransitionOutcome parkForHuman(TransitionContext ctx) {
        ParkedReason reason = ParkedReason.forInconclusive(ctx.verdict(), ctx.missingEvidence());
        if (reason == null) {
            return verdictMismatch(TransitionEvent.PARK_FOR_HUMAN, ctx.verdict());
        }
        return TransitionOutcome.allowed(DeductionStatus.PARKED, reason);
    }

    private static TransitionOutcome recordResult(DeductionStatus from, TransitionContext ctx) {
        if (from != DeductionStatus.DISPUTED) {
            return illegal(from, TransitionEvent.RECORD_RESULT);
        }
        if (!ctx.hasDisputeRecord()) {
            return TransitionOutcome.rejected("NO_DISPUTE", "a dispute record is required");
        }
        String requested = ctx.requestedResult();
        if (requested == null || (!requested.equals("WON") && !requested.equals("LOST"))) {
            return TransitionOutcome.rejected("INVALID_RESULT", "result must be WON or LOST");
        }
        return TransitionOutcome.allowed(DeductionStatus.valueOf(requested));
    }

    private static TransitionOutcome verdictMismatch(TransitionEvent event, VerdictValue verdict) {
        return TransitionOutcome.rejected("VERDICT_MISMATCH",
                event + " is not allowed for verdict " + verdict);
    }

    private static TransitionOutcome illegal(DeductionStatus from, TransitionEvent event) {
        return TransitionOutcome.rejected("ILLEGAL_TRANSITION", event + " is not allowed from " + from);
    }
}
