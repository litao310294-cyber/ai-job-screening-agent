package com.aijob.analyzer.retrieval.provider;

import com.aijob.analyzer.retrieval.port.EmbeddingClient;
import com.aijob.analyzer.retrieval.port.EmbeddingResponse;
import com.aijob.analyzer.retrieval.provider.dto.OpenAiEmbeddingRequest;
import com.aijob.analyzer.retrieval.provider.dto.OpenAiEmbeddingResponse;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;

public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private final RestClient.Builder restClientBuilder;
    private final OpenAiCompatibleEmbeddingProperties properties;
    private final com.aijob.analyzer.llm.ProviderCallLog callLog;

    public OpenAiCompatibleEmbeddingClient(RestClient.Builder restClientBuilder, OpenAiCompatibleEmbeddingProperties properties) {
        this(restClientBuilder, properties, null);
    }

    public OpenAiCompatibleEmbeddingClient(RestClient.Builder restClientBuilder, OpenAiCompatibleEmbeddingProperties properties,
                                          com.aijob.analyzer.llm.ProviderCallLog callLog) {
        this.restClientBuilder = restClientBuilder;
        this.properties = properties;
        this.callLog = callLog;
    }

    @Override
    public EmbeddingResponse embed(String modelIdentity, String text) {
        if (properties.baseUrl() == null || properties.baseUrl().isBlank()
                || properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("Embedding provider is not configured; set AI_EMBEDDING_BASE_URL and AI_EMBEDDING_API_KEY");
        }
        RestClient restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.apiKey())
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw new RuntimeException("Embedding API error: " + response.getStatusCode());
                })
                .build();
        OpenAiEmbeddingRequest request = new OpenAiEmbeddingRequest(modelIdentity, List.of(text));

        String callId = callLog == null ? null : callLog.start("EMBEDDING", modelIdentity, text.length());
        try {
        OpenAiEmbeddingResponse response = restClient.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(OpenAiEmbeddingResponse.class);

        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new RuntimeException("Empty or null response from embedding provider");
        }

        List<Float> floatList = response.data().getFirst().embedding();
        float[] vector = new float[floatList.size()];
        for (int i = 0; i < floatList.size(); i++) {
            vector[i] = floatList.get(i);
        }

        if (callLog != null) callLog.finish(callId, "SUCCESS", response.usage() == null ? null : response.usage().promptTokens(), null, null);
        return new EmbeddingResponse(
                vector,
                modelIdentity,
                response.model(),
                response.usage() != null ? response.usage().promptTokens() : null
        );
        } catch (RuntimeException e) {
            if (callLog != null) callLog.finish(callId, "FAILED", null, null, null);
            throw e;
        }
    }
}
