package com.deductionaudit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.evaluation")
public record EvaluationProperties(
        Duration totalBudget,
        Duration p95Target,
        Duration modelRoundTimeout,
        Duration toolTimeout,
        int maxToolCalls,
        int maxAttempts,
        Duration retryMinRemaining) {

    public EvaluationProperties {
        totalBudget = totalBudget == null ? Duration.ofSeconds(45) : totalBudget;
        p95Target = p95Target == null ? Duration.ofSeconds(30) : p95Target;
        modelRoundTimeout = modelRoundTimeout == null ? Duration.ofSeconds(12) : modelRoundTimeout;
        toolTimeout = toolTimeout == null ? Duration.ofSeconds(3) : toolTimeout;
        maxToolCalls = maxToolCalls <= 0 ? 6 : maxToolCalls;
        maxAttempts = maxAttempts <= 0 ? 2 : maxAttempts;
        retryMinRemaining = retryMinRemaining == null ? Duration.ofSeconds(12) : retryMinRemaining;
    }
}
