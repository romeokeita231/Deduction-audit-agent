package com.deductionaudit.shared;

public class WindowExpiredException extends ApiException {

    public WindowExpiredException() {
        super(409, "WINDOW_EXPIRED", "the dispute window has passed");
    }
}
