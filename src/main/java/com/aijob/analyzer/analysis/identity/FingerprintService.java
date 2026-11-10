package com.aijob.analyzer.analysis.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

public class FingerprintService {

    public JobFingerprint jobFingerprint(JobFingerprintInput input) {
        validate(input);
        String payload = buildCanonicalPayload(input);
        return new JobFingerprint(sha256(payload));
    }

    private void validate(JobFingerprintInput input) {
        if (input.title() == null || input.title().isBlank()) {
            throw new IllegalArgumentException("title cannot be null or blank");
        }
        if (input.description() == null || input.description().isBlank()) {
            throw new IllegalArgumentException("description cannot be null or blank");
        }
    }

    private String buildCanonicalPayload(JobFingerprintInput input) {
        StringBuilder sb = new StringBuilder();
        sb.append("title=").append(normalizeScalar(input.title())).append("\n");
        sb.append("city=").append(normalizeNullableScalar(input.city())).append("\n");
        sb.append("internshipDays=").append(input.internshipDays() == null ? "null" : input.internshipDays()).append("\n");
        sb.append("duration=").append(normalizeNullableScalar(input.duration())).append("\n");
        sb.append("salary=").append(normalizeNullableScalar(input.salary())).append("\n");
        sb.append("description=").append(normalizeDescription(input.description()));
        return sb.toString();
    }

    private String normalizeScalar(String value) {
        if (value == null) return "";
        String normalized = value.trim()
                .replace("\r\n", "\n")
                .replace("\r", "\n");
        // collapse repeated horizontal whitespace: space (0x20) and tab (0x09)
        return normalized.replaceAll("[ \t]+", " ");
    }

    private String normalizeNullableScalar(String value) {
        if (value == null || value.isBlank()) return "";
        return normalizeScalar(value);
    }

    private String normalizeDescription(String value) {
        if (value == null) return "";
        // preserve line structure except newline normalization and outer trim
        return value.trim()
                .replace("\r\n", "\n")
                .replace("\r", "\n");
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
