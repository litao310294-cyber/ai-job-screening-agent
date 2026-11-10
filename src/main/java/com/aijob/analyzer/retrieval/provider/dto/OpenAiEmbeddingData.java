package com.aijob.analyzer.retrieval.provider.dto;

import java.util.List;

public record OpenAiEmbeddingData(
        String object,
        int index,
        List<Float> embedding) {
}
