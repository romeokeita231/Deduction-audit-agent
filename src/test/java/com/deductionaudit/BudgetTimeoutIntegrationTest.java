package com.deductionaudit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "app.evaluation.total-budget=2s",
        "app.evaluation.model-round-timeout=1s"
})
class BudgetTimeoutIntegrationTest extends IntegrationTestBase {

    private static final String TIMEOUT = "18181818-1818-1818-1818-181818181818";
    private static final String DISPUTE = "11111111-1111-1111-1111-111111111111";

    @Test
    void anExhaustedBudgetParksWithThePartialTraceKept() {
        ResponseEntity<String> response = post("/deductions/" + TIMEOUT + "/evaluate");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(body(response).path("status").asText()).isEqualTo("PARKED");
        assertThat(body(response).path("parkedReason").asText()).isEqualTo("EVAL_FAILED");

        JsonNode detail = detail(TIMEOUT);
        assertThat(detail.path("attempts")).hasSize(1);
        assertThat(detail.path("attempts").get(0).path("outcome").asText()).isEqualTo("TIMEOUT");
        assertThat(detail.path("trace").size()).isGreaterThan(0);
        assertThat(detail.path("trace").get(0).path("error").asText()).contains("model round timeout");
        assertThat(detail.path("evaluation").isNull()).isTrue();
    }

    @Test
    void theConcurrencyGuardStillHolds() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Callable<Integer> call = () -> {
                    start.await();
                    return post("/deductions/" + DISPUTE + "/evaluate").getStatusCode().value();
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(202, 409);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM evaluation_attempt WHERE deduction_id = ?::uuid",
                    Integer.class, DISPUTE)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
