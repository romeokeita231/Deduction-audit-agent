package com.deductionaudit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.embedding")
public record EmbeddingProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        int dimensions) {

    public EmbeddingProperties {
        provider = provider == null ? "http" : provider;
        baseUrl = baseUrl == null ? "" : baseUrl;
        apiKey = apiKey == null ? "" : apiKey;
        model = model == null ? "text-embedding-v3" : model;
        dimensions = dimensions <= 0 ? 1024 : dimensions;
    }
}
