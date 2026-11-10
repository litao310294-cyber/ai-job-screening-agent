package com.aijob.analyzer.profile.domain;

public record RawResume(
        String userId,
        String sourceId,
        String sourceTitle,
        String rawText,
        String resumeFingerprint) {
}
