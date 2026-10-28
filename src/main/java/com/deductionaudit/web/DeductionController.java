package com.deductionaudit.web;

import com.deductionaudit.app.ActionOutcome;
import com.deductionaudit.app.ActionService;
import com.deductionaudit.app.DetailService;
import com.deductionaudit.app.EvaluationResult;
import com.deductionaudit.app.EvaluationService;
import com.deductionaudit.app.QueueService;
import com.deductionaudit.domain.DeductionCategory;
import com.deductionaudit.domain.DeductionStatus;
import com.deductionaudit.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
public class DeductionController {

    private final QueueService queue;
    private final DetailService details;
    private final EvaluationService evaluations;
    private final ActionService actions;

    public DeductionController(QueueService queue, DetailService details, EvaluationService evaluations,
                               ActionService actions) {
        this.queue = queue;
        this.details = details;
        this.evaluations = evaluations;
        this.actions = actions;
    }

    public record DisputeRequest(String submittedBy, String note) {
    }

    public record CloseRequest(String outcome) {
    }

    public record ResultRequest(String result) {
    }

    public record EvaluationResponse(String deductionId, String status, String parkedReason, Integer attempt,
                                     Object verdict) {
    }

    @GetMapping(value = "/deductions", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<QueueService.QueueRow> list(@RequestParam(required = false) String status,
                                            @RequestParam(required = false) String category) {
        List<DeductionStatus> statuses = status == null || status.isBlank() ? null
                : Arrays.stream(status.split(",")).map(String::trim)
                .map(DeductionStatus::valueOf).toList();
        DeductionCategory categoryFilter = category == null || category.isBlank() ? null
                : DeductionCategory.valueOf(category);
        return queue.queue(statuses, categoryFilter);
    }

    @GetMapping(value = "/deductions/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public DetailService.DetailView detail(@PathVariable UUID id,
                                           @RequestParam(required = false) Integer attempt) {
        return details.detail(id, attempt);
    }

    @PostMapping(value = "/deductions/{id}/evaluate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EvaluationResponse> evaluate(@PathVariable UUID id) {
        EvaluationResult result = evaluations.evaluate(id);
        EvaluationResponse body = new EvaluationResponse(result.deductionId().toString(),
                result.status().name(),
                result.parkedReason() == null ? null : result.parkedReason().name(),
                result.attemptNo(), result.verdict());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @PostMapping(value = "/deductions/{id}/dispute", produces = MediaType.APPLICATION_JSON_VALUE)
    public ActionOutcome dispute(@PathVariable UUID id,
                                 @RequestBody(required = false) DisputeRequest request) {
        DisputeRequest body = request == null ? new DisputeRequest(null, null) : request;
        return actions.dispute(id, body.submittedBy(), body.note());
    }

    @PostMapping(value = "/deductions/{id}/close", produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ActionOutcome close(@PathVariable UUID id, @RequestBody CloseRequest request) {
        return actions.close(id, request == null ? null : request.outcome());
    }

    @PostMapping(value = "/deductions/{id}/result", produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ActionOutcome result(@PathVariable UUID id, @RequestBody ResultRequest request) {
        if (request == null || request.result() == null) {
            throw ApiException.badRequest("INVALID_RESULT", "result must be WON or LOST");
        }
        return actions.recordResult(id, request.result());
    }
}
