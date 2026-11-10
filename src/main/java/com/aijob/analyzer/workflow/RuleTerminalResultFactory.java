package com.aijob.analyzer.workflow;

import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.workflow.domain.RuleResult;

public interface RuleTerminalResultFactory {
    JobAnalysisResult from(RuleResult ruleResult);
}
