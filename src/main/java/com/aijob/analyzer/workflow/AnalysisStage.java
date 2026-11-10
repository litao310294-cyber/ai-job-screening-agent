package com.aijob.analyzer.workflow;

import com.aijob.analyzer.workflow.domain.StageType;

public interface AnalysisStage {
    StageType type();
    StageResult execute(AnalysisContext context);
}
