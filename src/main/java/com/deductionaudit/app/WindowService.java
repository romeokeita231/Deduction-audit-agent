package com.deductionaudit.app;

import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.DeductionStatus;
import com.deductionaudit.domain.WindowArithmetic;
import com.deductionaudit.persistence.DeductionStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class WindowService {

    private final Clock clock;
    private final DeductionStore store;

    public WindowService(Clock clock, DeductionStore store) {
        this.clock = clock;
        this.store = store;
    }

    public LocalDate retailerToday(String timeZone) {
        return LocalDate.now(clock.withZone(ZoneId.of(timeZone)));
    }

    public boolean isExpired(Deduction deduction, String timeZone) {
        if (deduction.status() == DeductionStatus.DISPUTED || deduction.status().isTerminal()) {
            return false;
        }
        return WindowArithmetic.isExpired(deduction.windowEndsAt(), retailerToday(timeZone));
    }

    public Long daysRemaining(Deduction deduction, String timeZone) {
        if (deduction.status() == DeductionStatus.DISPUTED) {
            return null;
        }
        return WindowArithmetic.daysRemaining(deduction.windowEndsAt(), retailerToday(timeZone));
    }

    public LocalDate nowLocalDate() {
        return LocalDate.now(clock);
    }

    public String timeZoneOf(Deduction deduction) {
        return store.findRetailer(deduction.retailerId()).map(DeductionStore.Retailer::timeZone)
                .orElse("UTC");
    }
}
