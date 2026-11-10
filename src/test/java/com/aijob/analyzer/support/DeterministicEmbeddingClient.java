package com.aijob.analyzer.support;

import com.aijob.analyzer.retrieval.port.EmbeddingClient;
import com.aijob.analyzer.retrieval.port.EmbeddingResponse;

import java.util.HashMap;
import java.util.Map;

public class DeterministicEmbeddingClient implements EmbeddingClient {
    private final Map<String, float[]> registeredEmbeddings = new HashMap<>();
    private final String modelIdentity;
    private final String modelVersion;

    public DeterministicEmbeddingClient(String modelIdentity, String modelVersion) {
        this.modelIdentity = modelIdentity;
        this.modelVersion = modelVersion;
    }

    public void register(String text, float[] vector) {
        registeredEmbeddings.put(text, vector);
    }

    @Override
    public EmbeddingResponse embed(String modelIdentity, String text) {
        if (!this.modelIdentity.equals(modelIdentity)) {
            throw new IllegalArgumentException("Model identity mismatch. Expected: " + this.modelIdentity + ", Actual: " + modelIdentity);
        }
        float[] vector = registeredEmbeddings.get(text);
        if (vector == null) {
            throw new IllegalStateException("No deterministic embedding registered for input: " + text);
        }
        // Return a copy to prevent accidental modification of the registered vector
        return new EmbeddingResponse(vector.clone(), modelIdentity, modelVersion, text.length());
    }
}
