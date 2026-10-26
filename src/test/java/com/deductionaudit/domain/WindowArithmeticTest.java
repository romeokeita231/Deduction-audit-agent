package com.deductionaudit.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class WindowArithmeticTest {

    @Test
    void theWindowEndDayStillHasZeroDaysAndIsNotExpired() {
        LocalDate end = LocalDate.of(2026, 3, 10);
        assertThat(WindowArithmetic.daysRemaining(end, LocalDate.of(2026, 3, 10))).isZero();
        assertThat(WindowArithmetic.isExpired(end, LocalDate.of(2026, 3, 10))).isFalse();
        assertThat(WindowArithmetic.isExpired(end, LocalDate.of(2026, 3, 11))).isTrue();
        assertThat(WindowArithmetic.daysRemaining(end, LocalDate.of(2026, 3, 11))).isEqualTo(-1);
    }

    @Test
    void losAngelesAt2359StillHasADayLeft() {
        LocalDate today = LocalDate.ofInstant(Instant.parse("2026-03-10T06:59:00Z"),
                ZoneId.of("America/Los_Angeles"));
        assertThat(today).isEqualTo(LocalDate.of(2026, 3, 9));
        LocalDate end = LocalDate.of(2026, 3, 10);
        assertThat(WindowArithmetic.daysRemaining(end, today)).isEqualTo(1);
        assertThat(WindowArithmetic.isExpired(end, today)).isFalse();
    }

    @Test
    void tokyoJustAfterMidnightHasRolledToANewDay() {
        LocalDate today = LocalDate.ofInstant(Instant.parse("2026-03-09T15:01:00Z"),
                ZoneId.of("Asia/Tokyo"));
        assertThat(today).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(WindowArithmetic.daysRemaining(LocalDate.of(2026, 3, 9), today)).isEqualTo(-1);
        assertThat(WindowArithmetic.isExpired(LocalDate.of(2026, 3, 9), today)).isTrue();
        assertThat(WindowArithmetic.isExpired(LocalDate.of(2026, 3, 10), today)).isFalse();
    }
}
