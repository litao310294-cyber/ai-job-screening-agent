package com.aijob.analyzer.workflow.stage;

import com.aijob.analyzer.workflow.AnalysisContext;
import com.aijob.analyzer.workflow.AnalysisStage;
import com.aijob.analyzer.workflow.StageResult;
import com.aijob.analyzer.workflow.domain.RuleResult;
import com.aijob.analyzer.workflow.domain.RuleSignal;
import com.aijob.analyzer.workflow.domain.StageType;
import com.aijob.analyzer.workflow.port.RuleCheckService;

import java.util.Objects;

public class RuleCheckStage implements AnalysisStage {
    private final RuleCheckService ruleCheckService;

    public RuleCheckStage(RuleCheckService ruleCheckService) {
        this.ruleCheckService = Objects.requireNonNull(ruleCheckService);
    }

    @Override
    public StageType type() {
        return StageType.RULE_CHECK;
    }

    @Override
    public StageResult execute(AnalysisContext context) {
        RuleResult result = context.frozenRuleResult()!=null?context.frozenRuleResult():ruleCheckService.check(
                context.jobContext(),
                context.ruleCandidateProfile(),
                context.traceContext(),
                context.traceRecorder()
        );
        context.setRuleResult(result);

        if (result.signal() == RuleSignal.HARD_REJECT) {
            return StageResult.hardReject();
        }

        return StageResult.success();
    }
}
