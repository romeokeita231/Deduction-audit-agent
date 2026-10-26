package com.deductionaudit.embedding;

import com.deductionaudit.config.EmbeddingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class HttpEmbeddingClient implements EmbeddingClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EmbeddingProperties properties;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public HttpEmbeddingClient(EmbeddingProperties properties) {
        this.properties = properties;
    }

    @Override
    public float[] embed(String text) {
        if (properties.apiKey().isBlank()) {
            throw new EmbeddingUnavailableException("embedding api key is not configured");
        }
        try {
            String body = JSON.writeValueAsString(Map.of(
                    "model", properties.model(),
                    "input", text,
                    "dimensions", properties.dimensions(),
                    "encoding_format", "float"));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.baseUrl() + "/v1/embeddings"))
                    .timeout(Duration.ofSeconds(4))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new EmbeddingUnavailableException("embedding provider returned " + response.statusCode());
            }
            JsonNode embedding = JSON.readTree(response.body()).path("data").path(0).path("embedding");
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            return vector;
        } catch (EmbeddingUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingUnavailableException("embedding provider call failed: " + e.getMessage(), e);
        }
    }
}
