package com.aijob.analyzer.workflow;

import com.aijob.analyzer.analysis.domain.JobAnalysisResult;

public interface FallbackResultFactory {
    JobAnalysisResult fromRuleAndEvidence(AnalysisContext context);
}
