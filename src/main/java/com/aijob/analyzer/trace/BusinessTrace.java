package com.aijob.analyzer.trace;

import java.util.Collections;
import java.util.List;

public record BusinessTrace(
        String traceId,
        String taskId,
        List<TraceSpan> spans) {

    public BusinessTrace {
        spans = Collections.unmodifiableList(spans);
    }
}
