package com.aijob.analyzer.retrieval.domain;

public record FusedHit(
        String chunkId,
        String factId,
        double rrfScore,
        int rank) {
}
