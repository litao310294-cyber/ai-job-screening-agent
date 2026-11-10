package com.aijob.analyzer.trace;

import com.aijob.analyzer.workflow.domain.StageExecutionStatus;
import com.aijob.analyzer.workflow.domain.StageType;

import java.util.Map;

public interface TraceRecorder {
    void stageStarted(TraceContext trace, StageType stage, int attempt);

    void stageFinished(
            TraceContext trace,
            StageType stage,
            int attempt,
            StageExecutionStatus status,
            long durationMillis,
            String errorCode);

    void event(TraceContext trace, TraceEventType type, Map<String, Object> attributes);
}
