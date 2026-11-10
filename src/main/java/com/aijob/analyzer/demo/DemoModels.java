package com.aijob.analyzer.demo;

import com.aijob.analyzer.analysis.identity.AnalysisDependencySnapshot;
import com.aijob.analyzer.workflow.domain.AnalysisExecution;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class DemoModels {
    private DemoModels() {}
    public static final String USER_ID = "demo-li-tao";

    public record JobInput(
            @NotBlank @Size(max=64) String sourcePlatform,
            @Size(max=255) String sourceJobId, @Size(max=2048) String sourceUrl,
            @NotBlank @Size(max=255) String title, @Size(max=255) String company,
            @Size(max=255) String city, @Size(max=1024) String location,
            @Size(max=255) String salary, @Size(max=128) String education,
            @Size(max=128) String experience, @Size(max=50) List<@NotBlank @Size(max=128) String> skills,
            @Min(1) @Max(7) Integer internshipDays, @Size(max=255) String duration,
            @NotBlank @Size(max=50000) String description, boolean simulated,
            @Size(max=64) String captureMethod, Instant capturedAt,
            @Size(max=64) String direction, @Min(1) @Max(60) Integer internshipMonths,
            com.aijob.analyzer.analysis.domain.JobDetails.Salary compensation) {}

    public record JobSnapshot(String id, String fingerprint, JobInput job, Instant createdAt) {}
    public record AnalysisInput(@NotBlank @Size(max=128) String clientRequestId,
                                @NotBlank @Size(max=36) String jobSnapshotId,
                                @Positive Long expectedProfileVersion, @PositiveOrZero Long expectedRuleVersion) {}
    public record Operation(String id, String clientRequestId, String jobSnapshotId, String taskId,
                            String traceId, String status, AnalysisDependencySnapshot dependencies,
                            String reuseSource, Long canonicalArtifactId, AnalysisExecution execution,
                            String errorCode, Instant createdAt, Instant completedAt,
                            com.aijob.analyzer.rules.RuleModels.Snapshot rules,
                            com.aijob.analyzer.workflow.domain.RuleResult ruleResult, boolean stale) {}
    public record ApiError(String code, String message) {}
}
