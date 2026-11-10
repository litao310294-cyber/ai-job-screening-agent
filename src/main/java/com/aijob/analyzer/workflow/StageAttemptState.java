package com.aijob.analyzer.workflow;

import com.aijob.analyzer.workflow.domain.StageExecutionStatus;
import com.aijob.analyzer.workflow.domain.StageType;

import java.time.Instant;

public record StageAttemptState(
        StageType stageType,
        int attempt,
        StageExecutionStatus status,
        Instant startTime,
        Instant endTime) {
}
