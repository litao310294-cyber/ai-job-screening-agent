package com.aijob.analyzer.retrieval.port;

public interface EmbeddingClient {
    EmbeddingResponse embed(String modelIdentity, String text);
}
