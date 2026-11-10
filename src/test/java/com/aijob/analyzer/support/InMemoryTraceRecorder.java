package com.aijob.analyzer.support;

import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceEventType;
import com.aijob.analyzer.trace.TraceRecorder;
import com.aijob.analyzer.workflow.domain.StageExecutionStatus;
import com.aijob.analyzer.workflow.domain.StageType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class InMemoryTraceRecorder implements TraceRecorder {

    public record TraceEvent(TraceContext context, TraceEventType type, Map<String, Object> attributes) {}
    public record StageStartEvent(TraceContext context, StageType stage, int attempt) {}
    public record StageFinishEvent(TraceContext context, StageType stage, int attempt, StageExecutionStatus status, long durationMillis, String errorCode) {}

    private final List<TraceEvent> events = new ArrayList<>();
    private final List<StageStartEvent> stageStarts = new ArrayList<>();
    private final List<StageFinishEvent> stageFinishes = new ArrayList<>();

    @Override
    public void stageStarted(TraceContext trace, StageType stage, int attempt) {
        stageStarts.add(new StageStartEvent(trace, stage, attempt));
    }

    @Override
    public void stageFinished(TraceContext trace, StageType stage, int attempt, StageExecutionStatus status, long durationMillis, String errorCode) {
        stageFinishes.add(new StageFinishEvent(trace, stage, attempt, status, durationMillis, errorCode));
    }

    @Override
    public void event(TraceContext trace, TraceEventType type, Map<String, Object> attributes) {
        events.add(new TraceEvent(trace, type, Map.copyOf(attributes)));
    }

    public List<TraceEvent> getEvents() {
        return List.copyOf(events);
    }

    public List<StageStartEvent> getStageStarts() {
        return List.copyOf(stageStarts);
    }

    public List<StageFinishEvent> getStageFinishes() {
        return List.copyOf(stageFinishes);
    }
}
