package com.deductionaudit.app;

import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.DeductionStatus;
import com.deductionaudit.domain.TransitionContext;
import com.deductionaudit.domain.TransitionEvent;
import com.deductionaudit.persistence.DeductionStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ExpiryService {

    private final DeductionStore store;
    private final WindowService windows;
    private final TransitionApplier transitions;

    public ExpiryService(DeductionStore store, WindowService windows, TransitionApplier transitions) {
        this.store = store;
        this.windows = windows;
        this.transitions = transitions;
    }

    @Transactional
    public int expireDue() {
        int expired = 0;
        List<Deduction> candidates = store.findByStatuses(List.of(DeductionStatus.NEW,
                DeductionStatus.PARKED, DeductionStatus.AWAITING_ACTION));
        for (Deduction deduction : candidates) {
            if (windows.isExpired(deduction, windows.timeZoneOf(deduction))) {
                transitions.apply(deduction, TransitionEvent.EXPIRE, TransitionContext.atWindow(true), null);
                expired++;
            }
        }
        return expired;
    }
}
