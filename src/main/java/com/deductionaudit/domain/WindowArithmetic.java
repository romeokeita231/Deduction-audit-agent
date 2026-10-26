package com.deductionaudit.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public final class WindowArithmetic {

    private WindowArithmetic() {
    }

    public static LocalDate windowEndsAt(LocalDate deductionDate, int windowDays) {
        return deductionDate.plusDays(windowDays);
    }

    public static boolean isExpired(LocalDate windowEndsAt, LocalDate retailerLocalDate) {
        return retailerLocalDate.isAfter(windowEndsAt);
    }

    public static long daysRemaining(LocalDate windowEndsAt, LocalDate retailerLocalDate) {
        return ChronoUnit.DAYS.between(retailerLocalDate, windowEndsAt);
    }
}
