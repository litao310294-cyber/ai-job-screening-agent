package com.aijob.analyzer.trace;

import com.aijob.analyzer.workflow.domain.StageExecutionStatus;
import com.aijob.analyzer.workflow.domain.StageType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SafeTraceRecorderTest {

    @Test
    void should_swallow_exceptions_from_delegate() {
        TraceRecorder delegate = mock(TraceRecorder.class);
        doThrow(new RuntimeException("sink failed")).when(delegate).stageStarted(any(), any(), anyInt());
        doThrow(new RuntimeException("sink failed")).when(delegate).stageFinished(any(), any(), anyInt(), any(), anyLong(), any());
        doThrow(new RuntimeException("sink failed")).when(delegate).event(any(), any(), any());

        SafeTraceRecorder safeRecorder = new SafeTraceRecorder(delegate);
        TraceContext context = new TraceContext("t1", "k1");

        assertDoesNotThrow(() -> safeRecorder.stageStarted(context, StageType.RULE_CHECK, 1));
        assertDoesNotThrow(() -> safeRecorder.stageFinished(context, StageType.RULE_CHECK, 1, StageExecutionStatus.SUCCESS, 10, null));
        assertDoesNotThrow(() -> safeRecorder.event(context, TraceEventType.TRANSITION, Map.of()));

        verify(delegate, times(1)).stageStarted(any(), any(), anyInt());
        verify(delegate, times(1)).stageFinished(any(), any(), anyInt(), any(), anyLong(), any());
        verify(delegate, times(1)).event(any(), any(), any());
    }
}
