package com.deductionaudit.domain;

public sealed interface TransitionOutcome {

    record Allowed(DeductionStatus target, ParkedReason parkedReason) implements TransitionOutcome {
    }

    record Rejected(String code, String message) implements TransitionOutcome {
    }

    static TransitionOutcome allowed(DeductionStatus target) {
        return new Allowed(target, null);
    }

    static TransitionOutcome allowed(DeductionStatus target, ParkedReason reason) {
        return new Allowed(target, reason);
    }

    static TransitionOutcome rejected(String code, String message) {
        return new Rejected(code, message);
    }
}
