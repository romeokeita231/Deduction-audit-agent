package com.deductionaudit.trace;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Repository
public class MongoTraceRecorder implements TraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(MongoTraceRecorder.class);
    private static final String COLLECTION = "trace_event";
    private static final int MAX_FIELD = 8000;

    private final MongoTemplate mongo;

    public MongoTraceRecorder(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void recordModelRound(UUID deductionId, int attemptNo, String model, String request, String response,
                                 Integer inputTokens, Integer outputTokens, long durationMs, String error) {
        write(new Document("type", "model")
                .append("name", model)
                .append("request", truncate(request))
                .append("response", truncate(response))
                .append("inputTokens", inputTokens)
                .append("outputTokens", outputTokens)
                .append("durationMs", durationMs)
                .append("error", truncate(error)), deductionId, attemptNo);
    }

    @Override
    public void recordToolCall(UUID deductionId, int attemptNo, String toolName, String arguments, String result,
                               long durationMs, List<String> evidenceIds, String error) {
        write(new Document("type", "tool")
                .append("name", toolName)
                .append("request", truncate(arguments))
                .append("response", truncate(result))
                .append("evidenceIds", evidenceIds == null ? List.of() : evidenceIds)
                .append("durationMs", durationMs)
                .append("error", truncate(error)), deductionId, attemptNo);
    }

    @Override
    public void recordDraft(UUID deductionId, int attemptNo, String model, String request, String response,
                            long durationMs, List<String> evidenceIds, String error) {
        write(new Document("type", "draft")
                .append("name", "drafter")
                .append("request", truncate(request))
                .append("response", truncate(response))
                .append("evidenceIds", evidenceIds == null ? List.of() : evidenceIds)
                .append("durationMs", durationMs)
                .append("error", truncate(error)), deductionId, attemptNo);
    }

    @Override
    public List<TraceEntry> read(UUID deductionId, int attemptNo) {
        try {
            Query query = Query.query(Criteria.where("deductionId").is(deductionId.toString())
                    .and("attemptNo").is(attemptNo));
            query.with(Sort.by(Sort.Direction.ASC, "at"));
            return mongo.find(query, Document.class, COLLECTION).stream().map(doc -> new TraceEntry(
                    deductionId,
                    attemptNo,
                    doc.getString("type"),
                    doc.getString("name"),
                    doc.getDate("at") == null ? null : doc.getDate("at").toInstant(),
                    doc.get("durationMs") == null ? 0L : ((Number) doc.get("durationMs")).longValue(),
                    doc.getString("request"),
                    doc.getString("response"),
                    doc.getList("evidenceIds", String.class) == null ? List.of()
                            : doc.getList("evidenceIds", String.class),
                    doc.get("inputTokens") == null ? null : ((Number) doc.get("inputTokens")).intValue(),
                    doc.get("outputTokens") == null ? null : ((Number) doc.get("outputTokens")).intValue(),
                    doc.getString("error"))).toList();
        } catch (RuntimeException e) {
            log.warn("trace read failed for {} attempt {}", deductionId, attemptNo, e);
            throw new TraceUnavailableException(
                    "the trace store could not be read for attempt " + attemptNo, e);
        }
    }

    private void write(Document doc, UUID deductionId, int attemptNo) {
        doc.append("deductionId", deductionId.toString())
                .append("attemptNo", attemptNo)
                .append("at", Date.from(Instant.now()));
        try {
            mongo.insert(doc, COLLECTION);
        } catch (RuntimeException e) {
            log.warn("trace write failed for {} attempt {}; business continues", deductionId, attemptNo, e);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_FIELD ? value : value.substring(0, MAX_FIELD);
    }
}
