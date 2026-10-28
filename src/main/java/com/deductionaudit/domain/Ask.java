package com.deductionaudit.domain;

public record Ask(Type type) {

    public enum Type {
        REVERSE_DEDUCTION
    }

    public static final Ask REVERSE_DEDUCTION = new Ask(Type.REVERSE_DEDUCTION);
}
