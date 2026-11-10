package com.aijob.analyzer.workflow;

import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.analysis.domain.JobDecision;
import com.aijob.analyzer.workflow.domain.RuleResult;

import java.util.List;

public class DefaultRuleTerminalResultFactory implements RuleTerminalResultFactory {

    @Override
    public JobAnalysisResult from(RuleResult ruleResult) {
        return new JobAnalysisResult(
                JobDecision.NOT_RECOMMEND,
                "deterministic hard constraint not satisfied",
                List.of(),
                ruleResult != null ? ruleResult.reasons() : List.of(),
                List.of(),
                "do not continue semantic matching for this execution",
                "Workflow terminated early due to rule violation"
        );
    }
}
