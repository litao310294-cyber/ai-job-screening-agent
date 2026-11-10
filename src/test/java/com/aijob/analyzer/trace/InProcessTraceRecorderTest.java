package com.aijob.analyzer.trace;

import com.aijob.analyzer.workflow.domain.StageExecutionStatus;
import com.aijob.analyzer.workflow.domain.StageType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class InProcessTraceRecorderTest {

    @Test
    void should_assign_unique_monotonic_sequence_per_trace_under_concurrency() throws Exception {
        TraceContext context = new TraceContext("trace-sequence", "task-1");
        InProcessTraceRecorder recorder = new InProcessTraceRecorder();
        int writers = 8;
        int eventsPerWriter = 25;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        IntStream.range(0, writers).forEach(writer -> Thread.startVirtualThread(() -> {
            try {
                start.await();
                for (int i = 0; i < eventsPerWriter; i++) {
                    recorder.event(context, TraceEventType.TRANSITION, Map.of("writer", writer, "index", i));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        }));
        start.countDown();
        done.await();

        List<TraceRecord> records = recorder.getRecords(context.traceId());
        assertEquals(writers * eventsPerWriter, records.size());
        assertEquals(records.size(), records.stream().map(TraceRecord::sequence).distinct().count());
        assertEquals(LongStreamRange(1, records.size()), records.stream().map(TraceRecord::sequence).toList());
    }

    private static List<Long> LongStreamRange(long start, long count) {
        return java.util.stream.LongStream.rangeClosed(start, count).boxed().toList();
    }

    @Test
    void should_record_and_reconstruct_trace() {
        TraceContext context = new TraceContext("trace-123", "task-456");
        InProcessTraceRecorder recorder = new InProcessTraceRecorder();

        // Stage 1: RULE_CHECK
        recorder.stageStarted(context, StageType.RULE_CHECK, 1);
        recorder.event(context, TraceEventType.TRANSITION, Map.of("next", "RETRIEVAL"));
        recorder.stageFinished(context, StageType.RULE_CHECK, 1, StageExecutionStatus.SUCCESS, 100, null);

        // Stage 2: LLM_ANALYSIS attempt 1 (Failure)
        recorder.stageStarted(context, StageType.LLM_ANALYSIS, 1);
        recorder.event(context, TraceEventType.RETRY_SCHEDULED, Map.of("reason", "timeout"));
        recorder.stageFinished(context, StageType.LLM_ANALYSIS, 1, StageExecutionStatus.FAILED, 500, "TIMEOUT");

        // Stage 2: LLM_ANALYSIS attempt 2 (Success)
        recorder.stageStarted(context, StageType.LLM_ANALYSIS, 2);
        recorder.stageFinished(context, StageType.LLM_ANALYSIS, 2, StageExecutionStatus.SUCCESS, 200, null);

        BusinessTrace trace = recorder.getTrace(context.traceId());
        assertNotNull(trace);
        assertEquals("trace-123", trace.traceId());
        assertEquals("task-456", trace.taskId());
        assertEquals(3, trace.spans().size());

        TraceSpan span1 = trace.spans().get(0);
        assertEquals(StageType.RULE_CHECK, span1.stageType());
        assertEquals(1, span1.attempt());
        assertEquals(StageExecutionStatus.SUCCESS, span1.status());
        assertEquals(1, span1.events().size());
        assertEquals(TraceEventType.TRANSITION, span1.events().get(0).type());

        TraceSpan span2 = trace.spans().get(1);
        assertEquals(StageType.LLM_ANALYSIS, span2.stageType());
        assertEquals(1, span2.attempt());
        assertEquals(StageExecutionStatus.FAILED, span2.status());
        assertEquals("TIMEOUT", span2.errorCode());

        TraceSpan span3 = trace.spans().get(2);
        assertEquals(StageType.LLM_ANALYSIS, span3.stageType());
        assertEquals(2, span3.attempt());
        assertEquals(StageExecutionStatus.SUCCESS, span3.status());
    }
}
