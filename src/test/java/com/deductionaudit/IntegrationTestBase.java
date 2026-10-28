package com.deductionaudit;

import com.deductionaudit.replay.ReplayChatModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "replay"})
@Testcontainers
@Sql(scripts = "classpath:seed.sql")
@Import(IntegrationTestBase.FixedClock.class)
public abstract class IntegrationTestBase {

    protected static final Instant FIXED_NOW = Instant.parse("2026-03-10T06:59:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
    }

    @TestConfiguration
    static class FixedClock {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected ReplayChatModel replay;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MongoTemplate mongo;

    protected final ObjectMapper json = new ObjectMapper();

    protected RestTestClient client;

    @BeforeEach
    void resetState() {
        replay.reset();
        mongo.dropCollection("trace_event");
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private static ResponseEntity<String> entity(EntityExchangeResult<String> result) {
        return new ResponseEntity<>(result.getResponseBody(), result.getResponseHeaders(), result.getStatus());
    }

    protected ResponseEntity<String> get(String path) {
        return entity(client.get().uri(path).exchange().returnResult(String.class));
    }

    protected ResponseEntity<String> getHtml(String path) {
        return entity(client.get().uri(path).accept(MediaType.TEXT_HTML).exchange().returnResult(String.class));
    }

    protected ResponseEntity<String> post(String path) {
        return entity(client.post().uri(path).exchange().returnResult(String.class));
    }

    protected ResponseEntity<String> postJson(String path, Object body) {
        return entity(client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange().returnResult(String.class));
    }

    protected ResponseEntity<String> postWithPolicy(String path, String policyVersion) {
        return entity(client.post().uri(path).header("X-Policy-Version", policyVersion)
                .exchange().returnResult(String.class));
    }

    protected JsonNode body(ResponseEntity<String> response) {
        try {
            return json.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("response body is not json: " + response.getBody(), e);
        }
    }

    protected JsonNode getJson(String path) {
        ResponseEntity<String> response = get(path);
        return body(response);
    }

    protected JsonNode evaluate(String deductionId) {
        return body(post("/deductions/" + deductionId + "/evaluate"));
    }

    protected JsonNode detail(String deductionId) {
        return getJson("/deductions/" + deductionId);
    }
}
