package com.deductionaudit.replay;

import java.util.List;

public record ReplayScenario(
        String defaultModel,
        java.util.Map<String, ReplayDeduction> deductions) {

    public record ReplayDeduction(List<ReplayAttempt> attempts) {
    }

    public record ReplayAttempt(List<ReplayRound> rounds) {
    }

    public record ReplayRound(Long delayMs, List<ReplayToolCall> toolCalls, String text, String error) {
    }

    public record ReplayToolCall(String name, String arguments) {
    }
}
