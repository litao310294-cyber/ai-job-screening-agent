package com.aijob.analyzer.analysis.identity;

public record RequirementIdentity(
        JobFingerprint jobFingerprint,
        ParserDependencyFingerprint parserDependencyFingerprint) {
    public RequirementIdentity {
        if (jobFingerprint == null) throw new IllegalArgumentException("jobFingerprint cannot be null");
        if (parserDependencyFingerprint == null) throw new IllegalArgumentException("parserDependencyFingerprint cannot be null");
    }
}
