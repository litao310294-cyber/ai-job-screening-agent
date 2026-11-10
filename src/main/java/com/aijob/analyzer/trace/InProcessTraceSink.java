package com.aijob.analyzer.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory V1 sink used for reconstruction and deterministic acceptance tests. */
public final class InProcessTraceSink implements TraceSink {
    private final Map<String, CopyOnWriteArrayList<TraceRecord>> records = new ConcurrentHashMap<>();

    @Override
    public void record(TraceRecord record) {
        records.computeIfAbsent(record.traceId(), ignored -> new CopyOnWriteArrayList<>()).add(record);
    }

    public List<TraceRecord> getRecords(String traceId) {
        return List.copyOf(records.getOrDefault(traceId, new CopyOnWriteArrayList<>()));
    }
}
