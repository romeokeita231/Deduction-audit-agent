package com.deductionaudit.web;

import com.deductionaudit.app.ActionService;
import com.deductionaudit.app.DetailService;
import com.deductionaudit.app.DisputeService;
import com.deductionaudit.app.EvaluationService;
import com.deductionaudit.app.QueueService;
import com.deductionaudit.domain.DeductionCategory;
import com.deductionaudit.domain.DeductionStatus;
import com.deductionaudit.shared.ApiException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Controller
public class UiController {

    private final QueueService queue;
    private final DetailService details;
    private final EvaluationService evaluations;
    private final ActionService actions;
    private final DisputeService disputes;

    public UiController(QueueService queue, DetailService details, EvaluationService evaluations,
                        ActionService actions, DisputeService disputes) {
        this.queue = queue;
        this.details = details;
        this.evaluations = evaluations;
        this.actions = actions;
        this.disputes = disputes;
    }

    @GetMapping(value = "/queue", produces = MediaType.TEXT_HTML_VALUE)
    public String queue(@RequestParam(required = false) String status,
                        @RequestParam(required = false) String category, Model model) {
        List<DeductionStatus> statuses = status == null || status.isBlank() ? null
                : Arrays.stream(status.split(",")).map(String::trim).map(DeductionStatus::valueOf).toList();
        DeductionCategory categoryFilter = category == null || category.isBlank() ? null
                : DeductionCategory.valueOf(category);
        model.addAttribute("rows", queue.queue(statuses, categoryFilter));
        model.addAttribute("status", status);
        model.addAttribute("category", category);
        return "queue";
    }

    @GetMapping(value = "/deductions/{id}", produces = MediaType.TEXT_HTML_VALUE)
    public String detail(@PathVariable UUID id,
                         @RequestParam(required = false) Integer attempt,
                         @RequestParam(required = false, defaultValue = "false") boolean all,
                         Model model) {
        DetailService.DetailView view = details.detail(id, attempt);
        model.addAttribute("detail", view);
        model.addAttribute("attempt", attempt);
        return "detail";
    }

    @PostMapping(value = "/deductions/{id}/evaluate", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String evaluate(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            evaluations.evaluate(id);
        } catch (ApiException e) {
            redirect.addFlashAttribute("error", e.code() + ": " + e.getMessage());
        }
        return "redirect:/deductions/" + id;
    }

    @PostMapping(value = "/deductions/{id}/dispute", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String dispute(@PathVariable UUID id, @RequestParam(required = false) String note,
                          @RequestParam(required = false) String submittedBy,
                          RedirectAttributes redirect) {
        try {
            disputes.dispute(id, submittedBy, note);
        } catch (ApiException e) {
            redirect.addFlashAttribute("error", e.code() + ": " + e.getMessage());
        }
        return "redirect:/deductions/" + id;
    }

    @PostMapping(value = "/deductions/{id}/close", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String close(@PathVariable UUID id, @RequestParam String outcome,
                        RedirectAttributes redirect) {
        try {
            actions.close(id, outcome);
        } catch (ApiException e) {
            redirect.addFlashAttribute("error", e.code() + ": " + e.getMessage());
        }
        return "redirect:/deductions/" + id;
    }

    @PostMapping(value = "/deductions/{id}/result", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String result(@PathVariable UUID id, @RequestParam String result, RedirectAttributes redirect) {
        try {
            actions.recordResult(id, result);
        } catch (ApiException e) {
            redirect.addFlashAttribute("error", e.code() + ": " + e.getMessage());
        }
        return "redirect:/deductions/" + id;
    }
}
