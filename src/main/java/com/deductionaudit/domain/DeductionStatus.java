package com.deductionaudit.domain;

public enum DeductionStatus {
    NEW,
    PARKED,
    EVALUATING,
    AWAITING_ACTION,
    DISPUTED,
    ACCEPTED,
    WRITTEN_OFF,
    EXPIRED,
    WON,
    LOST;

    public boolean isTerminal() {
        return this == ACCEPTED || this == WRITTEN_OFF || this == EXPIRED || this == WON || this == LOST;
    }
}
