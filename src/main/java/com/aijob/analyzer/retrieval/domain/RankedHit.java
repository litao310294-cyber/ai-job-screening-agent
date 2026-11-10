package com.aijob.analyzer.retrieval.domain;

public record RankedHit(
        String chunkId,
        String factId,
        double rawScore,
        int rank) {
}
