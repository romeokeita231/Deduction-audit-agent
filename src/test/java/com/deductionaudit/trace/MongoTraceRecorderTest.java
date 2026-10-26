package com.deductionaudit.trace;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MongoTraceRecorderTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final MongoTraceRecorder recorder = new MongoTraceRecorder(mongo);

    @Test
    void aBrokenTraceStoreNeverFailsBusiness() {
        when(mongo.insert(any(Document.class), eq("trace_event")))
                .thenThrow(new RuntimeException("mongo is down"));
        assertThatCode(() -> recorder.recordToolCall(UUID.randomUUID(), 1, "getBol", "{}", "{}", 1,
                List.of("BOL:SHP-1"), null)).doesNotThrowAnyException();
    }

    @Test
    void aBrokenTraceReadAnnouncesItself() {
        when(mongo.find(any(Query.class), eq(Document.class), anyString()))
                .thenThrow(new RuntimeException("mongo is down"));
        assertThatThrownBy(() -> recorder.read(UUID.randomUUID(), 1))
                .isInstanceOf(TraceUnavailableException.class);
    }
}
