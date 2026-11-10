package com.aijob.analyzer.llm;

import com.aijob.analyzer.analysis.identity.LlmSemanticFingerprint;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class LlmSemanticSnapshotFactory {

    public LlmSemanticFingerprint createFingerprint(LlmSemanticSnapshot snapshot) {
        String canonical = String.format("%s|%s|%s|%s|%s|%d|%s",
                snapshot.promptVersion(),
                snapshot.modelIdentity(),
                snapshot.schemaVersion(),
                snapshot.generationConfig().temperature().toPlainString(),
                snapshot.generationConfig().topP().toPlainString(),
                snapshot.generationConfig().maxOutputTokens(),
                snapshot.generationConfig().responseFormat());

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return new LlmSemanticFingerprint(HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }
}
