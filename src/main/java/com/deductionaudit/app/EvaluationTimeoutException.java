package com.deductionaudit.app;

public class EvaluationTimeoutException extends RuntimeException {

    public EvaluationTimeoutException() {
        super("evaluation exceeded the wall clock budget");
    }
}
