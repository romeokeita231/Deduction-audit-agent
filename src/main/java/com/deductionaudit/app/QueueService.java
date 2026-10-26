package com.deductionaudit.app;

import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.DeductionCategory;
import com.deductionaudit.domain.DeductionStatus;
import com.deductionaudit.domain.ParkedReason;
import com.deductionaudit.domain.VerdictValue;
import com.deductionaudit.persistence.DeductionStore;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class QueueService {

    private final DeductionStore store;
    private final WindowService windows;

    public QueueService(DeductionStore store, WindowService windows) {
        this.store = store;
        this.windows = windows;
    }

    public record QueueRow(
            UUID id,
            String retailerId,
            String retailerName,
            String reasonCode,
            DeductionCategory category,
            BigDecimal amount,
            String currency,
            LocalDate deductionDate,
            LocalDate windowEndsAt,
            Long daysRemaining,
            DeductionStatus status,
            ParkedReason parkedReason,
            VerdictValue verdict,
            boolean expired) {
    }

    public List<QueueRow> queue(List<DeductionStatus> statuses, DeductionCategory category) {
        List<Deduction> deductions = statuses == null || statuses.isEmpty()
                ? store.findAll()
                : store.findByStatuses(statuses);
        Map<String, DeductionStore.Retailer> retailers = new HashMap<>();
        for (DeductionStore.Retailer retailer : store.findAllRetailers()) {
            retailers.put(retailer.id(), retailer);
        }
        Map<UUID, VerdictValue> verdicts = new HashMap<>();
        store.latestVerdicts().forEach((id, verdict) -> verdicts.put(id, verdict.verdict()));
        return deductions.stream()
                .filter(deduction -> category == null || deduction.category() == category)
                .map(deduction -> {
                    DeductionStore.Retailer retailer = retailers.get(deduction.retailerId());
                    String timeZone = retailer == null ? "UTC" : retailer.timeZone();
                    Long daysRemaining = windows.daysRemaining(deduction, timeZone);
                    boolean expired = deduction.status() == DeductionStatus.EXPIRED
                            || windows.isExpired(deduction, timeZone);
                    return new QueueRow(deduction.id(), deduction.retailerId(),
                            retailer == null ? deduction.retailerId() : retailer.name(),
                            deduction.reasonCode(), deduction.category(), deduction.amount(),
                            deduction.currency(), deduction.deductionDate(), deduction.windowEndsAt(),
                            daysRemaining, deduction.status(), deduction.parkedReason(),
                            verdicts.get(deduction.id()), expired);
                })
                .sorted(Comparator.comparing(QueueRow::daysRemaining,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}
