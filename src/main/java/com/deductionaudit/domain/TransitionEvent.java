package com.deductionaudit.domain;

public enum TransitionEvent {
    CATEGORIZE_UNMAPPED,
    START_EVALUATION,
    RETRY_EVALUATION,
    FAIL_EVALUATION,
    VERDICT_RECORDED,
    REQUEUE,
    DISPUTE,
    ACCEPT,
    WRITE_OFF,
    PARK_FOR_HUMAN,
    EXPIRE,
    RECORD_RESULT
}
