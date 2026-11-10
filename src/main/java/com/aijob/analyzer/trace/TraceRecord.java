package com.aijob.analyzer.trace;

import com.aijob.analyzer.workflow.domain.StageExecutionStatus;
import com.aijob.analyzer.workflow.domain.StageType;

import java.time.Instant;
import java.util.Map;

/** Immutable, sequence-bearing observation emitted by a TraceRecorder. */
public record TraceRecord(
        String traceId,
        String taskId,
        long sequence,
        Instant timestamp,
        String operation,
        StageType stage,
        int attempt,
        StageExecutionStatus status,
        String errorCode,
        TraceEventType eventType,
        Map<String, Object> attributes) {
    public TraceRecord {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
