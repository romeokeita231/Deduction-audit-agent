package com.deductionaudit.replay;

import java.util.List;
import java.util.Map;

public record ReplayScenario(
        String defaultModel,
        Map<String, ReplayDeduction> deductions,
        Map<String, List<ReplayDraft>> drafts) {

    public ReplayScenario {
        drafts = drafts == null ? Map.of() : Map.copyOf(drafts);
    }

    public record ReplayDeduction(List<ReplayAttempt> attempts) {
    }

    public record ReplayAttempt(List<ReplayRound> rounds) {
    }

    public record ReplayRound(Long delayMs, List<ReplayToolCall> toolCalls, String text, String error) {
    }

    public record ReplayToolCall(String name, String arguments) {
    }

    public record ReplayDraft(String text) {
    }
}
