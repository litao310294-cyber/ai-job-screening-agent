package com.aijob.analyzer.trace;

public record TraceContext(
        String traceId,
        String taskId) {
}
