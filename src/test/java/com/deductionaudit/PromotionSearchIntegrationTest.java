package com.deductionaudit;

import com.deductionaudit.embedding.EmbeddingClient;
import com.deductionaudit.persistence.EvidenceStore;
import com.deductionaudit.tools.EvidenceTools;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionSearchIntegrationTest extends IntegrationTestBase {

    private static final List<String> COLD = List.of("cold", "refriger", "frozen", "temperature", "chilled");
    private static final List<String> RECORDS = List.of("record", "log", "documentation", "ledger");
    private static final List<String> KEEP = List.of("keep", "kept", "retain", "stored", "store", "held", "hold");
    private static final List<String> TIME = List.of("day", "month", "ninety", "thirty", "fourteen", "week", "year");

    private static final String PARAPHRASE = "How long are the refrigeration records retained?";

    @TestConfiguration
    static class ConceptEmbedding {

        @Bean
        @Primary
        EmbeddingClient conceptEmbeddingClient() {
            return PromotionSearchIntegrationTest::conceptVector;
        }
    }

    private static float[] conceptVector(String text) {
        float[] vector = new float[1024];
        String lower = text.toLowerCase();
        add(vector, 0, lower, COLD);
        add(vector, 1, lower, RECORDS);
        add(vector, 2, lower, KEEP);
        add(vector, 3, lower, TIME);
        return vector;
    }

    private static void add(float[] vector, int dimension, String text, List<String> concepts) {
        for (String concept : concepts) {
            if (text.contains(concept)) {
                vector[dimension] = vector[dimension] + 1f;
                return;
            }
        }
    }

    @Autowired
    private EvidenceStore evidenceStore;

    @Autowired
    private EvidenceTools tools;

    @Autowired
    private EmbeddingClient embeddingClient;

    @BeforeEach
    void embedEveryClause() {
        List<Map<String, Object>> clauses = jdbc.queryForList(
                "SELECT doc_id, clause_no, text FROM promotion_agreement_clause");
        for (Map<String, Object> clause : clauses) {
            evidenceStore.updateClauseEmbedding((String) clause.get("doc_id"),
                    (String) clause.get("clause_no"),
                    embeddingClient.embed((String) clause.get("text")));
        }
    }

    @Test
    void twentySyntheticAgreementsAreStored() {
        assertThat(evidenceStore.agreementCount()).isEqualTo(20);
        assertThat(evidenceStore.clauseCount()).isEqualTo(22);
    }

    @Test
    void aParaphraseWithoutSharedKeywordsFindsTheClauseWithProvenance() throws Exception {
        JsonNode result = json.readTree(tools.searchPromotionAgreements(PARAPHRASE));
        assertThat(result.path("found").asBoolean()).isTrue();
        assertThat(result.path("evidenceIds").isArray()).isTrue();
        assertThat(result.path("evidenceIds").get(0).asText()).isEqualTo("PROMO:PA-08·§1");
        JsonNode clause = result.path("clauses").get(0);
        assertThat(clause.path("docId").asText()).isEqualTo("PA-08");
        assertThat(clause.path("clauseNo").asText()).isEqualTo("1");
        assertThat(clause.path("similarity").asDouble()).isGreaterThanOrEqualTo(0.4);
    }

    @Test
    void evidenceIdsSurviveReEmbedding() throws Exception {
        String first = tools.searchPromotionAgreements(PARAPHRASE);
        embedEveryClause();
        String second = tools.searchPromotionAgreements(PARAPHRASE);
        assertThat(json.readTree(second).path("evidenceIds"))
                .isEqualTo(json.readTree(first).path("evidenceIds"));
    }

    @Test
    void aMissReturnsNothingRatherThanTheNearestJunk() throws Exception {
        JsonNode result = json.readTree(tools.searchPromotionAgreements("guitar music festival tickets"));
        assertThat(result.path("found").asBoolean()).isFalse();
        assertThat(result.path("evidenceIds").size()).isZero();
        assertThat(result.path("clauses").size()).isZero();
    }

    @Test
    void aFabricatedClauseNumberIsBlockedByTheClosedSet() throws Exception {
        assertThat(tools.searchPromotionAgreements("PA-13")).contains("PROMO:PA-13·§3.2");
        var response = post("/deductions/20202020-2020-2020-2020-202020202020/evaluate");
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(body(response).path("parkedReason").asText()).isEqualTo("EVAL_FAILED");
    }

    @Test
    void thePriceVarianceFixtureIsStillGreen() {
        JsonNode result = evaluate("55555555-5555-5555-5555-555555555555");
        assertThat(result.path("verdict").path("verdict").asText()).isEqualTo("DISPUTABLE");
        assertThat(result.path("verdict").path("supportingEvidence").toString())
                .contains("PRICE:SKU-1:2025-12-01");
    }
}
