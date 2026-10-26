package com.deductionaudit.app;

import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.DeductionStatus;
import com.deductionaudit.domain.VerdictRecord;
import com.deductionaudit.persistence.DeductionStore;
import com.deductionaudit.trace.TraceEntry;
import com.deductionaudit.trace.TraceRecorder;
import com.deductionaudit.web.ApiException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class DetailService {

    private final DeductionStore store;
    private final WindowService windows;
    private final TraceRecorder trace;

    public DetailService(DeductionStore store, WindowService windows, TraceRecorder trace) {
        this.store = store;
        this.windows = windows;
        this.trace = trace;
    }

    public record AttemptView(
            int attempt,
            java.time.Instant startedAt,
            java.time.Instant endedAt,
            Long durationMs,
            String outcome,
            int toolCallCount) {
    }

    public record TransitionView(
            int seq,
            String from,
            String to,
            String event,
            Integer attempt,
            java.time.Instant occurredAt) {
    }

    public record EvaluationView(
            int attempt,
            String model,
            String verdict,
            List<String> missingEvidence,
            List<String> references,
            List<String> supportingEvidence,
            String reasoning,
            String promptVersion,
            String policyVersion,
            int mappingVersion,
            java.time.Instant evaluatedAt) {
    }

    public record DetailView(
            UUID id,
            String retailerId,
            String retailerName,
            String reasonCode,
            String category,
            java.math.BigDecimal amount,
            String currency,
            java.time.LocalDate deductionDate,
            java.time.Instant receivedAt,
            java.time.LocalDate windowEndsAt,
            String status,
            String parkedReason,
            Long daysRemaining,
            boolean expired,
            List<AttemptView> attempts,
            EvaluationView evaluation,
            List<TransitionView> transitions,
            List<TraceEntry> trace) {
    }

    public DetailView detail(UUID id, Integer requestedAttempt) {
        Deduction deduction = store.find(id).orElseThrow(() -> ApiException.notFound("deduction not found"));
        String timeZone = windows.timeZoneOf(deduction);
        String retailerName = store.findRetailer(deduction.retailerId())
                .map(DeductionStore.Retailer::name).orElse(deduction.retailerId());
        List<AttemptView> attempts = store.attempts(id).stream()
                .map(attempt -> new AttemptView(attempt.attemptNo(), attempt.startedAt(), attempt.endedAt(),
                        attempt.startedAt() == null || attempt.endedAt() == null ? null
                                : Duration.between(attempt.startedAt(), attempt.endedAt()).toMillis(),
                        attempt.outcome(), attempt.toolCallCount()))
                .toList();
        int attemptNo = requestedAttempt != null ? requestedAttempt
                : attempts.isEmpty() ? 0 : attempts.getLast().attempt();
        VerdictRecord verdict = store.verdicts(id).stream()
                .filter(record -> record.attemptNo() == attemptNo)
                .findFirst()
                .orElse(attemptNo == 0 ? store.latestVerdict(id).orElse(null) : null);
        EvaluationView evaluation = verdict == null ? null : new EvaluationView(
                verdict.attemptNo(),
                verdict.model(),
                verdict.verdict().name(),
                verdict.missingEvidence().stream().map(Enum::name).toList(),
                verdict.references(),
                verdict.supportingEvidence(),
                verdict.reasoning(),
                verdict.promptVersion(),
                verdict.policyVersion(),
                verdict.mappingVersion(),
                verdict.evaluatedAt());
        List<TransitionView> transitions = store.transitions(id).stream()
                .map(row -> new TransitionView(row.seq(),
                        row.from() == null ? null : row.from().name(),
                        row.to().name(), row.event(), row.attemptNo(), row.occurredAt()))
                .toList();
        return new DetailView(deduction.id(), deduction.retailerId(), retailerName, deduction.reasonCode(),
                deduction.category() == null ? null : deduction.category().name(), deduction.amount(),
                deduction.currency(), deduction.deductionDate(), deduction.receivedAt(),
                deduction.windowEndsAt(), deduction.status().name(),
                deduction.parkedReason() == null ? null : deduction.parkedReason().name(),
                windows.daysRemaining(deduction, timeZone),
                deduction.status() == DeductionStatus.EXPIRED || windows.isExpired(deduction, timeZone),
                attempts, evaluation, transitions,
                attemptNo == 0 ? List.of() : trace.read(id, attemptNo));
    }
}
