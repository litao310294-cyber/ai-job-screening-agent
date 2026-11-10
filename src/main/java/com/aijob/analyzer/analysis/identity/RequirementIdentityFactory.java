package com.aijob.analyzer.analysis.identity;

import com.aijob.analyzer.llm.LlmGenerationConfigSnapshot;
import com.aijob.analyzer.llm.LlmSemanticSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class RequirementIdentityFactory {
    public RequirementIdentity create(JobFingerprint jobFingerprint, LlmSemanticSnapshot parserSnapshot) {
        return new RequirementIdentity(jobFingerprint, new ParserDependencyFingerprint(sha256(parserPayload(parserSnapshot))));
    }

    static String parserPayload(LlmSemanticSnapshot snapshot) {
        return "promptVersion=" + normalized(snapshot.promptVersion()) + "\n"
                + "modelIdentity=" + normalized(snapshot.modelIdentity()) + "\n"
                + "schemaVersion=" + normalized(snapshot.schemaVersion()) + "\n"
                + generationPayload(snapshot.generationConfig());
    }

    static String generationPayload(LlmGenerationConfigSnapshot config) {
        return "temperature=" + decimal(config == null ? null : config.temperature()) + "\n"
                + "topP=" + decimal(config == null ? null : config.topP()) + "\n"
                + "maxOutputTokens=" + (config == null || config.maxOutputTokens() == null ? "null" : config.maxOutputTokens()) + "\n"
                + "responseFormat=" + normalized(config == null ? null : config.responseFormat());
    }

    static String normalized(String value) { return value == null ? "null" : value.trim(); }
    static String decimal(java.math.BigDecimal value) { return value == null ? "null" : value.stripTrailingZeros().toPlainString(); }
    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
