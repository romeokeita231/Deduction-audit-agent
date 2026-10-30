package com.deductionaudit.eval;

import com.deductionaudit.app.DisputeService;
import com.deductionaudit.app.EvaluationResult;
import com.deductionaudit.app.EvaluationService;
import com.deductionaudit.domain.Deduction;
import com.deductionaudit.domain.VerdictValue;
import com.deductionaudit.eval.corpus.CorpusCase;
import com.deductionaudit.eval.corpus.CorpusLoader;
import com.deductionaudit.eval.corpus.CorpusLoader.LoadedCase;
import com.deductionaudit.persistence.DeductionStore;
import com.deductionaudit.shared.ApiException;
import com.deductionaudit.trace.TraceEntry;
import com.deductionaudit.trace.TraceRecorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Component
public class EvalCaseDriver {

    public static final int MAX_CONCURRENCY = 8;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CorpusSeeder seeder;
    private final EvaluationService evaluation;
    private final DisputeService disputes;
    private final DeductionStore store;
    private final TraceRecorder trace;
    private final Clock clock;

    public EvalCaseDriver(CorpusSeeder seeder, EvaluationService evaluation, DisputeService disputes,
                          DeductionStore store, TraceRecorder trace, Clock clock) {
        this.seeder = seeder;
        this.evaluation = evaluation;
        this.disputes = disputes;
        this.store = store;
        this.trace = trace;
        this.clock = clock;
    }

    public NdjsonExporter.EvalRow run(LoadedCase loaded) {
        Instant started = Instant.now();
        seeder.seed(loaded);
        UUID id = loaded.deduction().id();
        EvaluationResult result = evaluation.evaluate(id);

        String narrative = null;
        JsonNode claim = null;
        JsonNode digest = null;
        if (result.verdict() != null && result.verdict().verdict() == VerdictValue.DISPUTABLE) {
            try {
                DisputeService.Disputed disputed = disputes.dispute(id, "eval", "eval:live");
                claim = JSON.valueToTree(disputed.document().claim());
                narrative = disputed.document().narrative();
                digest = JSON.valueToTree(disputed.document().digest());
            } catch (ApiException e) {
                if (e.status() != 503) {
                    throw e;
                }
            }
        }

        long latency = Duration.between(started, Instant.now()).toMillis();
        Deduction after = store.find(id).orElseThrow();
        CorpusCase facts = loaded.facts();
        int attemptNo = result.attemptNo() == null ? 0 : result.attemptNo();
        String amount = claim == null ? after.amount().setScale(2).toPlainString()
                : claim.get("amount").asText();
        List<String> expected = new ArrayList<>();
        for (String token : facts.expectedSupportingEvidence()) {
            expected.add(CorpusLoader.resolveEvidence(token, clock));
        }
        return new NdjsonExporter.EvalRow(
                NdjsonExporter.SCHEMA_VERSION,
                facts.caseId(),
                attemptNo,
                after.status().name(),
                after.parkedReason() == null ? null : after.parkedReason().name(),
                result.verdict() == null ? null : result.verdict().verdict().name(),
                claim,
                narrative,
                digest,
                latency,
                toolCalls(id, attemptNo),
                NdjsonExporter.contract(amount),
                facts.oracleBranch(),
                facts.expectedState().name(),
                facts.expectedParkedReason() == null ? null : facts.expectedParkedReason().name(),
                List.copyOf(expected));
    }

    public List<NdjsonExporter.EvalRow> runAll(List<LoadedCase> cases, int concurrency) {
        int workers = Math.max(1, Math.min(concurrency, Math.min(MAX_CONCURRENCY, cases.size())));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<NdjsonExporter.EvalRow>> futures = new ArrayList<>();
            for (LoadedCase loaded : cases) {
                futures.add(pool.submit(() -> run(loaded)));
            }
            List<NdjsonExporter.EvalRow> rows = new ArrayList<>(futures.size());
            for (Future<NdjsonExporter.EvalRow> future : futures) {
                rows.add(await(future));
            }
            return List.copyOf(rows);
        } finally {
            pool.shutdown();
        }
    }

    private static NdjsonExporter.EvalRow await(Future<NdjsonExporter.EvalRow> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the evaluation run was interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("the evaluation run failed", cause);
        }
    }

    private List<NdjsonExporter.ToolCall> toolCalls(UUID id, int attemptNo) {
        Map<String, long[]> aggregate = new TreeMap<>();
        for (TraceEntry entry : trace.read(id, attemptNo)) {
            if (!"tool".equals(entry.type())) {
                continue;
            }
            String name = entry.name() == null ? "unknown" : entry.name();
            long[] values = aggregate.computeIfAbsent(name, key -> new long[2]);
            values[0]++;
            values[1] += entry.durationMs();
        }
        List<NdjsonExporter.ToolCall> calls = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : aggregate.entrySet()) {
            calls.add(new NdjsonExporter.ToolCall(entry.getKey(), (int) entry.getValue()[0],
                    entry.getValue()[1]));
        }
        return List.copyOf(calls);
    }
}
