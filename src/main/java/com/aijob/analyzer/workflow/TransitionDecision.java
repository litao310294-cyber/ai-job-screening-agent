package com.aijob.analyzer.workflow;

import com.aijob.analyzer.workflow.domain.StageType;
import com.aijob.analyzer.workflow.domain.WorkflowStatus;

public sealed interface TransitionDecision {
    record Continue(StageType nextStage) implements TransitionDecision {}
    record Terminal(WorkflowStatus status) implements TransitionDecision {}
    record InvokeFallback() implements TransitionDecision {}
}
