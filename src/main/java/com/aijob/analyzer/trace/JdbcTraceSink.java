package com.aijob.analyzer.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

/** Called inside SafeTraceRecorder. A write failure is isolated from business execution. */
public final class JdbcTraceSink implements TraceSink {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcTraceSink(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    @Override public void record(TraceRecord record) {
        String payload;
        try { payload = json.writeValueAsString(record); }
        catch (Exception e) { throw new IllegalStateException("Trace serialization failed"); }
        jdbc.update("INSERT INTO demo_trace_record(trace_id,sequence_number,task_id,record_json) VALUES (?,?,?,?)",
                record.traceId(), record.sequence(), record.taskId(), payload);
    }
}
