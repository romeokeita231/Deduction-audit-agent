package com.deductionaudit.trace;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoTraceRecorderTest {

    private static final MongoClient UNREACHABLE = MongoClients.create(MongoClientSettings.builder()
            .applyConnectionString(new ConnectionString("mongodb://127.0.0.1:1/trace"))
            .applyToClusterSettings(settings -> settings.serverSelectionTimeout(1, TimeUnit.SECONDS))
            .applyToSocketSettings(settings -> settings.connectTimeout(1, TimeUnit.SECONDS))
            .build());

    private final MongoTraceRecorder recorder = new MongoTraceRecorder(
            new MongoTemplate(new SimpleMongoClientDatabaseFactory(UNREACHABLE, "trace")));

    @AfterAll
    static void closeClient() {
        UNREACHABLE.close();
    }

    @Test
    void aBrokenTraceStoreNeverFailsBusiness() {
        assertThatCode(() -> recorder.recordToolCall(UUID.randomUUID(), 1, "getBol", "{}", "{}", 1,
                List.of("BOL:SHP-1"), null)).doesNotThrowAnyException();
        assertThatCode(() -> recorder.recordModelRound(UUID.randomUUID(), 1, "gpt-5", "{}", "{}",
                null, null, 1, null)).doesNotThrowAnyException();
    }

    @Test
    void aBrokenTraceReadAnnouncesItself() {
        assertThatThrownBy(() -> recorder.read(UUID.randomUUID(), 1))
                .isInstanceOf(TraceUnavailableException.class);
    }
}
