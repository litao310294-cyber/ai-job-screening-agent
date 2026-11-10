package com.aijob.analyzer.trace;

import com.aijob.analyzer.workflow.domain.StageExecutionStatus;
import com.aijob.analyzer.workflow.domain.StageType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public class InProcessTraceRecorder implements TraceRecorder {

    private record TraceEntry(String taskId, AtomicLong sequence, List<TraceSpan> spans) {}

    private final Map<String, TraceEntry> traces = new ConcurrentHashMap<>();
    private final Map<String, TraceSpan> activeSpans = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<TraceRecord>> records = new ConcurrentHashMap<>();
    private final TraceSink sink;

    public InProcessTraceRecorder() {
        this(new InProcessTraceSink());
    }

    public InProcessTraceRecorder(TraceSink sink) {
        this.sink = sink;
    }

    @Override
    public void stageStarted(TraceContext trace, StageType stage, int attempt) {
        TraceContext context = trace;
        TraceEntry entry = entry(context);
        long sequence = entry.sequence().incrementAndGet();
        emit(new TraceRecord(context.traceId(), context.taskId(), sequence, Instant.now(),
                "STAGE_STARTED", stage, attempt, StageExecutionStatus.RUNNING, null, null, Map.of()));
        TraceSpan span = new TraceSpan(stage, attempt, Instant.now(), sequence);
        String key = spanKey(trace.traceId(), stage, attempt);
        activeSpans.put(key, span);
        entry.spans().add(span);
    }

    @Override
    public void stageFinished(TraceContext trace, StageType stage, int attempt, StageExecutionStatus status, long durationMillis, String errorCode) {
        TraceEntry entry = entry(trace);
        emit(new TraceRecord(trace.traceId(), trace.taskId(), entry.sequence().incrementAndGet(), Instant.now(),
                "STAGE_FINISHED", stage, attempt, status, errorCode, null, Map.of("durationMillis", durationMillis)));
        String key = spanKey(trace.traceId(), stage, attempt);
        TraceSpan span = activeSpans.remove(key);
        if (span != null) {
            span.finish(status, durationMillis, errorCode);
        }
    }

    @Override
    public void event(TraceContext trace, TraceEventType type, Map<String, Object> attributes) {
        TraceEntry entry = entry(trace);
        emit(new TraceRecord(trace.traceId(), trace.taskId(), entry.sequence().incrementAndGet(), Instant.now(),
                "EVENT", null, 0, null, null, type, attributes));
        if (entry != null && !entry.spans().isEmpty()) {
            TraceSpan lastSpan = entry.spans().get(entry.spans().size() - 1);
            lastSpan.addEvent(new TraceEvent(type, Instant.now(), attributes));
        }
    }

    public BusinessTrace getTrace(String traceId) {
        TraceEntry entry = traces.get(traceId);
        if (entry == null) return null;
        return new BusinessTrace(traceId, entry.taskId(), entry.spans().stream()
                .sorted(java.util.Comparator.comparingLong(TraceSpan::sequence)).toList());
    }

    public List<TraceRecord> getRecords(String traceId) {
        return records.getOrDefault(traceId, new CopyOnWriteArrayList<>()).stream()
                .sorted(Comparator.comparingLong(TraceRecord::sequence)).toList();
    }

    private TraceEntry entry(TraceContext trace) {
        return traces.computeIfAbsent(trace.traceId(), ignored -> new TraceEntry(trace.taskId(), new AtomicLong(), new CopyOnWriteArrayList<>()));
    }

    private void emit(TraceRecord record) {
        records.computeIfAbsent(record.traceId(), ignored -> new CopyOnWriteArrayList<>()).add(record);
        sink.record(record);
    }

    private String spanKey(String traceId, StageType stage, int attempt) {
        return traceId + ":" + stage.name() + ":" + attempt;
    }
}
