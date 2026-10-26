package com.deductionaudit.web;

public class WindowExpiredException extends ApiException {

    public WindowExpiredException() {
        super(409, "WINDOW_EXPIRED", "the dispute window has passed");
    }
}
