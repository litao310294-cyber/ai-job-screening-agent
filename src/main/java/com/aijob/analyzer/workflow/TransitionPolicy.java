package com.aijob.analyzer.workflow;

import com.aijob.analyzer.workflow.domain.StageType;

public interface TransitionPolicy {
    TransitionDecision next(
            StageType current,
            StageResult result,
            AnalysisContext context);
}
