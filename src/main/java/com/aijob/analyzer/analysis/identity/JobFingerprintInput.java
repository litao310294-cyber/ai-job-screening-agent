package com.aijob.analyzer.analysis.identity;

public record JobFingerprintInput(
        String sourcePlatform,
        String sourceJobId,
        String title,
        String city,
        Integer internshipDays,
        String duration,
        String salary,
        String description) {
}
