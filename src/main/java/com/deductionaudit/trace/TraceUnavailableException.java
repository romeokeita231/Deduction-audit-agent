package com.deductionaudit.trace;

public class TraceUnavailableException extends RuntimeException {

    public TraceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
