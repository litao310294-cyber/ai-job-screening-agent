package com.aijob.analyzer.retrieval.fusion;

public record RrfConfig(int k) {
    public static final RrfConfig DEFAULT = new RrfConfig(60);
}
