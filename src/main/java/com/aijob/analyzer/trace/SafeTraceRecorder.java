package com.aijob.analyzer.trace;

import com.aijob.analyzer.workflow.domain.StageExecutionStatus;
import com.aijob.analyzer.workflow.domain.StageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class SafeTraceRecorder implements TraceRecorder {
    private static final Logger log = LoggerFactory.getLogger(SafeTraceRecorder.class);
    private final TraceRecorder delegate;

    public SafeTraceRecorder(TraceRecorder delegate) {
        this.delegate = delegate;
    }

    @Override
    public void stageStarted(TraceContext trace, StageType stage, int attempt) {
        try {
            delegate.stageStarted(trace, stage, attempt);
        } catch (Exception e) {
            log.error("Trace recorder stageStarted failure: {}", e.getMessage());
        }
    }

    @Override
    public void stageFinished(TraceContext trace, StageType stage, int attempt, StageExecutionStatus status, long durationMillis, String errorCode) {
        try {
            delegate.stageFinished(trace, stage, attempt, status, durationMillis, errorCode);
        } catch (Exception e) {
            log.error("Trace recorder stageFinished failure: {}", e.getMessage());
        }
    }

    @Override
    public void event(TraceContext trace, TraceEventType type, Map<String, Object> attributes) {
        try {
            delegate.event(trace, type, attributes);
        } catch (Exception e) {
            log.error("Trace recorder event failure: {}", e.getMessage());
        }
    }
}
