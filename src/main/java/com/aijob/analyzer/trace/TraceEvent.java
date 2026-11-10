package com.aijob.analyzer.trace;

import java.time.Instant;
import java.util.Map;

public record TraceEvent(
        TraceEventType type,
        Instant timestamp,
        Map<String, Object> attributes) {
}
