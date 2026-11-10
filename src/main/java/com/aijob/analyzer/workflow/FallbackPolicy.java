package com.aijob.analyzer.workflow;

import com.aijob.analyzer.workflow.domain.StageType;
import com.aijob.analyzer.workflow.domain.WorkflowError;

public interface FallbackPolicy {
    boolean allows(StageType failedStage, WorkflowError error);
}
