package com.deductionaudit;

import com.deductionaudit.app.ExpiryService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineIntegrationTest extends IntegrationTestBase {

    private static final String DISPUTE = "11111111-1111-1111-1111-111111111111";
    private static final String STANDS = "22222222-2222-2222-2222-222222222222";
    private static final String NO_POD = "33333333-3333-3333-3333-333333333333";
    private static final String UNMAPPED = "44444444-4444-4444-4444-444444444444";
    private static final String PRICE_LOW = "55555555-5555-5555-5555-555555555555";
    private static final String PRICE_HIGH = "66666666-6666-6666-6666-666666666666";
    private static final String PRICE_MISSING = "77777777-7777-7777-7777-777777777777";
    private static final String PRICE_NO_INTERVAL = "88888888-8888-8888-8888-888888888888";
    private static final String PROMO = "99999999-9999-9999-9999-999999999999";
    private static final String PROMO_NO_SETTLEMENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String EXPIRED = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String RETRY = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String CLOSED_SET = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String TOOL_LIMIT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String TOKYO_TODAY = "15151515-1515-1515-1515-151515151515";
    private static final String LA_TODAY = "16161616-1616-1616-1616-161616161616";
    private static final String PARKED = "17171717-1717-1717-1717-171717171717";
    private static final String FORGED_CLAUSE = "20202020-2020-2020-2020-202020202020";

    @Autowired
    private ExpiryService expiryService;

    private static JsonNode row(JsonNode rows, String id) {
        for (JsonNode row : rows) {
            if (row.path("id").asText().equals(id)) {
                return row;
            }
        }
        throw new AssertionError("deduction " + id + " is not in the queue");
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static List<String> toolNames(JsonNode trace) {
        List<String> names = new ArrayList<>();
        trace.forEach(entry -> {
            if ("tool".equals(entry.path("type").asText())) {
                names.add(entry.path("name").asText());
            }
        });
        return names;
    }

    @Test
    void queueIsSortedAndCarriesTheNumbers() {
        JsonNode rows = getJson("/deductions");
        long previous = Long.MIN_VALUE;
        for (JsonNode row : rows) {
            assertThat(row.path("daysRemaining").isNull()).isFalse();
            assertThat(row.path("daysRemaining").asLong()).isGreaterThanOrEqualTo(previous);
            previous = row.path("daysRemaining").asLong();
        }

        JsonNode expired = row(rows, EXPIRED);
        assertThat(expired.path("daysRemaining").asLong()).isEqualTo(-1);
        assertThat(expired.path("expired").asBoolean()).isTrue();
        assertThat(expired.path("retailerId").asText()).isEqualTo("TOKYO");

        assertThat(row(rows, TOKYO_TODAY).path("daysRemaining").asLong()).isZero();
        assertThat(row(rows, TOKYO_TODAY).path("expired").asBoolean()).isFalse();
        assertThat(row(rows, LA_TODAY).path("daysRemaining").asLong()).isEqualTo(1);

        JsonNode dispute = row(rows, DISPUTE);
        assertThat(dispute.path("daysRemaining").asLong()).isEqualTo(25);
        assertThat(dispute.path("amount").asDouble()).isEqualTo(480.00);
        assertThat(dispute.path("category").asText()).isEqualTo("SHORTAGE");
        assertThat(dispute.path("retailerId").asText()).isEqualTo("NYC");
        assertThat(dispute.path("status").asText()).isEqualTo("NEW");

        JsonNode parked = row(rows, PARKED);
        assertThat(parked.path("status").asText()).isEqualTo("PARKED");
        assertThat(parked.path("parkedReason").asText()).isEqualTo("EVIDENCE_GAP");
        assertThat(parked.path("daysRemaining").asLong()).isZero();
    }

    @Test
    void queueFiltersActAndIntersect() {
        JsonNode parked = getJson("/deductions?status=PARKED");
        assertThat(parked).hasSize(1);
        assertThat(parked.get(0).path("id").asText()).isEqualTo(PARKED);

        JsonNode price = getJson("/deductions?category=PRICE_VARIANCE");
        assertThat(price).hasSize(4);

        JsonNode intersection = getJson("/deductions?status=NEW&category=PRICE_VARIANCE");
        assertThat(intersection).hasSize(4);
        assertThat(row(intersection, PRICE_LOW).path("category").asText()).isEqualTo("PRICE_VARIANCE");
        assertThat(ids(intersection)).doesNotContain(UNMAPPED, DISPUTE);

        JsonNode all = getJson("/deductions?status=ALL");
        assertThat(all.size()).isEqualTo(19);
    }

    private static List<String> ids(JsonNode rows) {
        List<String> values = new ArrayList<>();
        rows.forEach(row -> values.add(row.path("id").asText()));
        return values;
    }

    @Test
    void shortageVerdictCarriesTraceAndDetail() {
        ResponseEntity<String> response = post("/deductions/" + DISPUTE + "/evaluate");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode result = body(response);
        assertThat(result.path("status").asText()).isEqualTo("AWAITING_ACTION");
        assertThat(result.path("attempt").asInt()).isEqualTo(1);
        assertThat(result.path("verdict").path("verdict").asText()).isEqualTo("DISPUTABLE");
        assertThat(result.path("verdict").path("supportingEvidence").size()).isEqualTo(2);

        JsonNode detail = detail(DISPUTE);
        assertThat(detail.path("status").asText()).isEqualTo("AWAITING_ACTION");
        JsonNode evaluation = detail.path("evaluation");
        assertThat(evaluation.path("model").asText()).isEqualTo("replay");
        assertThat(evaluation.path("policyVersion").asText()).hasSize(12);
        assertThat(evaluation.path("promptVersion").asText()).hasSize(12);
        assertThat(evaluation.path("mappingVersion").asInt()).isEqualTo(1);
        assertThat(texts(evaluation.path("supportingEvidence"))).contains("BOL:SHP-DISPUTE", "POD:SHP-DISPUTE");
        assertThat(texts(evaluation.path("references"))).contains("EDI812:" + DISPUTE);

        assertThat(detail.path("attempts")).hasSize(1);
        JsonNode attempt = detail.path("attempts").get(0);
        assertThat(attempt.path("toolCallCount").asInt()).isEqualTo(3);
        assertThat(attempt.path("durationMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(attempt.path("outcome").asText()).isEqualTo("VERDICT");

        JsonNode trace = detail.path("trace");
        assertThat(toolNames(trace)).contains("getAsn", "getBol", "getPod");
        assertThat(trace.size()).isGreaterThan(3);
        assertThat(trace.get(0).path("durationMs").asLong()).isGreaterThanOrEqualTo(0);

        assertThat(post("/deductions/" + DISPUTE + "/evaluate").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aCleanDeliveryStands() {
        JsonNode result = evaluate(STANDS);
        assertThat(result.path("status").asText()).isEqualTo("AWAITING_ACTION");
        assertThat(result.path("verdict").path("verdict").asText()).isEqualTo("DEDUCTION_STANDS");
        assertThat(texts(result.path("verdict").path("supportingEvidence")))
                .containsExactly("BOL:SHP-STANDS", "POD:SHP-STANDS");
    }

    @Test
    void inconclusiveParksWithAGapAndCanBeRequeuedAndReRun() {
        ResponseEntity<String> response = post("/deductions/" + NO_POD + "/evaluate");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode result = body(response);
        assertThat(result.path("status").asText()).isEqualTo("PARKED");
        assertThat(result.path("parkedReason").asText()).isEqualTo("EVIDENCE_GAP");
        assertThat(result.path("attempt").asInt()).isEqualTo(1);
        assertThat(texts(result.path("verdict").path("missingEvidence"))).containsExactly("POD");

        ResponseEntity<String> rerun = post("/deductions/" + NO_POD + "/evaluate");
        assertThat(rerun.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rerunBody = body(rerun);
        assertThat(rerunBody.path("status").asText()).isEqualTo("AWAITING_ACTION");
        assertThat(rerunBody.path("attempt").asInt()).isEqualTo(2);

        JsonNode detail = detail(NO_POD);
        List<String> events = new ArrayList<>();
        detail.path("transitions").forEach(t -> events.add(t.path("event").asText()));
        assertThat(events).containsSubsequence("VERDICT_RECORDED", "PARK_FOR_HUMAN", "REQUEUE",
                "START_EVALUATION", "VERDICT_RECORDED");
        assertThat(detail.path("attempts")).hasSize(2);
    }

    @Test
    void anUnmappedReasonCodeParksAndRequeueStillConflicts() {
        ResponseEntity<String> response = post("/deductions/" + UNMAPPED + "/evaluate");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(body(response).path("parkedReason").asText()).isEqualTo("UNMAPPED_CODE");

        ResponseEntity<String> again = post("/deductions/" + UNMAPPED + "/evaluate");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(again).path("code").asText()).isEqualTo("UNMAPPED_CODE");
    }

    @Test
    void priceVarianceUsesOnlyTheIntervalThatCoversTheDeductionDate() {
        JsonNode low = evaluate(PRICE_LOW);
        assertThat(low.path("verdict").path("verdict").asText()).isEqualTo("DISPUTABLE");
        assertThat(texts(low.path("verdict").path("supportingEvidence")))
                .containsExactly("PRICE:SKU-1:2025-12-01", "INVOICE:INV-LOW");

        JsonNode high = evaluate(PRICE_HIGH);
        assertThat(high.path("verdict").path("verdict").asText()).isEqualTo("DEDUCTION_STANDS");
        assertThat(texts(high.path("verdict").path("supportingEvidence")))
                .contains("PRICE:SKU-1:2025-12-01", "INVOICE:INV-HIGH");

        ResponseEntity<String> missing = post("/deductions/" + PRICE_MISSING + "/evaluate");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(body(missing).path("parkedReason").asText()).isEqualTo("EVIDENCE_GAP");
        assertThat(texts(body(missing).path("verdict").path("missingEvidence")))
                .containsExactly("INVOICE_LINE");

        ResponseEntity<String> noInterval = post("/deductions/" + PRICE_NO_INTERVAL + "/evaluate");
        assertThat(noInterval.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(body(noInterval).path("parkedReason").asText()).isEqualTo("POLICY_GAP");
        assertThat(body(noInterval).path("verdict").path("missingEvidence").size()).isZero();

        JsonNode trace = detail(PRICE_LOW).path("trace");
        assertThat(toolNames(trace)).containsOnly("getPriceMaster", "getInvoiceLine");
    }

    @Test
    void aPolicyGapCanOnlyBeRequeuedWithANewPolicyVersion() {
        post("/deductions/" + PRICE_NO_INTERVAL + "/evaluate");
        String parkedVersion = jdbc.queryForObject(
                "SELECT parked_policy_version FROM deduction WHERE id = ?::uuid", String.class,
                PRICE_NO_INTERVAL);
        assertThat(parkedVersion).hasSize(12);

        ResponseEntity<String> unchanged = postWithPolicy("/deductions/" + PRICE_NO_INTERVAL + "/evaluate",
                parkedVersion);
        assertThat(unchanged.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(unchanged).path("code").asText()).isEqualTo("POLICY_UNCHANGED");

        ResponseEntity<String> changed = postWithPolicy("/deductions/" + PRICE_NO_INTERVAL + "/evaluate",
                "000000000000");
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(body(changed).path("attempt").asInt()).isEqualTo(2);
        assertThat(body(changed).path("parkedReason").asText()).isEqualTo("POLICY_GAP");
    }

    @Test
    void promotionVerdictCitesClausesAndSettlement() {
        JsonNode result = evaluate(PROMO);
        assertThat(result.path("verdict").path("verdict").asText()).isEqualTo("DISPUTABLE");
        assertThat(texts(result.path("verdict").path("supportingEvidence")))
                .containsExactly("PROMO:PA-07·§3.1", "PROMO:PA-07·§3.2", "SETTLE:PA-07");

        JsonNode trace = detail(PROMO).path("trace");
        assertThat(toolNames(trace)).containsOnly("searchPromotionAgreements", "getPromotionSettlement");
        JsonNode search = null;
        for (JsonNode entry : trace) {
            if ("searchPromotionAgreements".equals(entry.path("name").asText())) {
                search = entry;
                break;
            }
        }
        assertThat(search).isNotNull();
        assertThat(search.path("request").asText()).contains("PA-07");
        assertThat(search.path("response").asText()).contains("PA-07", "3.1");

        ResponseEntity<String> noSettlement = post("/deductions/" + PROMO_NO_SETTLEMENT + "/evaluate");
        assertThat(noSettlement.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(body(noSettlement).path("parkedReason").asText()).isEqualTo("EVIDENCE_GAP");
        assertThat(texts(body(noSettlement).path("verdict").path("missingEvidence")))
                .containsExactly("PROMOTION_SETTLEMENT");
    }

    @Test
    void fabricatedEvidenceParksWithoutRetrying() {
        ResponseEntity<String> closeSet = post("/deductions/" + CLOSED_SET + "/evaluate");
        assertThat(closeSet.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(body(closeSet).path("parkedReason").asText()).isEqualTo("EVAL_FAILED");
        JsonNode closeSetDetail = detail(CLOSED_SET);
        assertThat(closeSetDetail.path("attempts")).hasSize(1);
        assertThat(closeSetDetail.path("trace").size()).isGreaterThan(0);

        ResponseEntity<String> clause = post("/deductions/" + FORGED_CLAUSE + "/evaluate");
        assertThat(clause.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(body(clause).path("parkedReason").asText()).isEqualTo("EVAL_FAILED");
    }

    @Test
    void theToolBudgetStopsAtSixCalls() {
        JsonNode result = evaluate(TOOL_LIMIT);
        assertThat(result.path("status").asText()).isEqualTo("AWAITING_ACTION");
        JsonNode detail = detail(TOOL_LIMIT);
        assertThat(detail.path("attempts").get(0).path("toolCallCount").asInt()).isEqualTo(6);
        assertThat(toolNames(detail.path("trace"))).hasSize(6);
    }

    @Test
    void anInfrastructureFailureIsRetriedOnceThenSucceeds() {
        JsonNode result = evaluate(RETRY);
        assertThat(result.path("status").asText()).isEqualTo("AWAITING_ACTION");
        assertThat(result.path("attempt").asInt()).isEqualTo(2);
        JsonNode detail = detail(RETRY);
        assertThat(detail.path("attempts")).hasSize(2);
        assertThat(detail.path("attempts").get(0).path("outcome").asText()).isEqualTo("INFRA_FAILED");
        assertThat(detail.path("attempts").get(1).path("outcome").asText()).isEqualTo("VERDICT");

        JsonNode firstTrace = getJson("/deductions/" + RETRY + "?attempt=1").path("trace");
        assertThat(firstTrace.size()).isGreaterThan(0);
        assertThat(firstTrace.get(0).path("error").asText()).contains("429");
    }

    @Test
    void anExpiredWindowRejectsAndPersistsExpired() {
        ResponseEntity<String> response = post("/deductions/" + EXPIRED + "/evaluate");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(response).path("code").asText()).isEqualTo("WINDOW_EXPIRED");
        assertThat(detail(EXPIRED).path("status").asText()).isEqualTo("EXPIRED");

        int swept = expiryService.expireDue();
        assertThat(swept).isZero();
        assertThat(detail(EXPIRED).path("status").asText()).isEqualTo("EXPIRED");
    }

    @Test
    void theDailySweepFlipsExpiredRows() {
        int swept = expiryService.expireDue();
        assertThat(swept).isEqualTo(1);
        assertThat(detail(EXPIRED).path("status").asText()).isEqualTo("EXPIRED");
        assertThat(row(getJson("/deductions?status=EXPIRED"), EXPIRED).path("expired").asBoolean()).isTrue();
    }

    @Test
    void theMoneyActionsAndTheirGuardsHold() {
        evaluate(DISPUTE);
        ResponseEntity<String> disputed = postJson("/deductions/" + DISPUTE + "/dispute",
                Map.of("submittedBy", "ar-1", "note", "carrier loss"));
        assertThat(disputed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(disputed).path("status").asText()).isEqualTo("DISPUTED");
        assertThat(body(disputed).path("disputeId").asText()).isNotBlank();
        assertThat(detail(DISPUTE).path("daysRemaining").isNull()).isTrue();

        assertThat(postJson("/deductions/" + DISPUTE + "/close", Map.of("outcome", "ACCEPTED"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(postJson("/deductions/" + DISPUTE + "/result", Map.of("result", "BOGUS"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<String> won = postJson("/deductions/" + DISPUTE + "/result",
                Map.of("result", "WON", "closedAt", "2026-03-12T00:00:00Z"));
        assertThat(won.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(won).path("status").asText()).isEqualTo("WON");
        assertThat(jdbc.queryForObject("SELECT result FROM dispute WHERE deduction_id = ?::uuid",
                String.class, DISPUTE)).isEqualTo("WON");
        assertThat(jdbc.queryForObject("SELECT closed_at FROM dispute WHERE deduction_id = ?::uuid",
                java.time.OffsetDateTime.class, DISPUTE)).isNotNull();

        assertThat(post("/deductions/" + DISPUTE + "/evaluate").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(postJson("/deductions/" + DISPUTE + "/dispute", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(postJson("/deductions/" + DISPUTE + "/close", Map.of("outcome", "WRITTEN_OFF"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(postJson("/deductions/" + DISPUTE + "/result", Map.of("result", "LOST"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void standsCanBeAcceptedButNotDisputed() {
        evaluate(STANDS);
        assertThat(postJson("/deductions/" + STANDS + "/dispute", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<String> accepted = postJson("/deductions/" + STANDS + "/close",
                Map.of("outcome", "ACCEPTED"));
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(accepted).path("status").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    void inconclusiveAndParkedRowsCannotBeDisputedAndCanBeWrittenOff() {
        post("/deductions/" + NO_POD + "/evaluate");
        ResponseEntity<String> dispute = postJson("/deductions/" + NO_POD + "/dispute", Map.of());
        assertThat(dispute.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(dispute).path("code").asText()).isEqualTo("ILLEGAL_TRANSITION");

        ResponseEntity<String> writtenOff = postJson("/deductions/" + NO_POD + "/close",
                Map.of("outcome", "WRITTEN_OFF"));
        assertThat(writtenOff.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(writtenOff).path("status").asText()).isEqualTo("WRITTEN_OFF");
    }

    @Test
    void detailIs404ForUnknownIdsAndPagesRender() {
        assertThat(get("/deductions/00000000-0000-0000-0000-000000000000").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> queuePage = getHtml("/queue");
        assertThat(queuePage.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queuePage.getHeaders().getContentType().toString()).contains("text/html");
        assertThat(queuePage.getBody()).contains("New York Retail");
        ResponseEntity<String> detailPage = getHtml("/deductions/" + DISPUTE);
        assertThat(detailPage.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detailPage.getBody()).contains("EDI812:" + DISPUTE);
    }
}
