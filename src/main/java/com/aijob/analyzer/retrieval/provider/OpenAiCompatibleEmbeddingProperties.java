package com.aijob.analyzer.retrieval.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.embedding")
public record OpenAiCompatibleEmbeddingProperties(
        String baseUrl,
        String apiKey,
        String model) {
}
