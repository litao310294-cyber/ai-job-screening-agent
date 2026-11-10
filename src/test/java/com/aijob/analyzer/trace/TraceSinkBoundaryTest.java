package com.aijob.analyzer.trace;

import com.aijob.analyzer.workflow.domain.StageType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class TraceSinkBoundaryTest {

    @Test
    void safe_recorder_absorbs_sink_failure_without_losing_business_call() {
        TraceSink failingSink = record -> { throw new IllegalStateException("sink down"); };
        SafeTraceRecorder recorder = new SafeTraceRecorder(new InProcessTraceRecorder(failingSink));

        assertDoesNotThrow(() -> recorder.stageStarted(new TraceContext("t", "task"), StageType.RULE_CHECK, 1));
    }
}
