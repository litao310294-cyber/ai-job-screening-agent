package com.aijob.analyzer.trace;

import com.aijob.analyzer.workflow.domain.StageExecutionStatus;
import com.aijob.analyzer.workflow.domain.StageType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TraceSpan {
    private final StageType stageType;
    private final int attempt;
    private final long sequence;
    private final Instant startTime;
    private Instant endTime;
    private long durationMillis;
    private StageExecutionStatus status;
    private String errorCode;
    private final List<TraceEvent> events = new ArrayList<>();

    public TraceSpan(StageType stageType, int attempt, Instant startTime) {
        this(stageType, attempt, startTime, 0L);
    }

    public TraceSpan(StageType stageType, int attempt, Instant startTime, long sequence) {
        this.stageType = stageType;
        this.attempt = attempt;
        this.startTime = startTime;
        this.sequence = sequence;
    }

    public void finish(StageExecutionStatus status, long durationMillis, String errorCode) {
        this.status = status;
        this.durationMillis = durationMillis;
        this.errorCode = errorCode;
        this.endTime = startTime.plusMillis(durationMillis);
    }

    public void addEvent(TraceEvent event) {
        this.events.add(event);
    }

    public StageType stageType() { return stageType; }
    public int attempt() { return attempt; }
    public long sequence() { return sequence; }
    public Instant startTime() { return startTime; }
    public Instant endTime() { return endTime; }
    public long durationMillis() { return durationMillis; }
    public StageExecutionStatus status() { return status; }
    public String errorCode() { return errorCode; }
    public List<TraceEvent> events() { return Collections.unmodifiableList(events); }
}
