package com.aijob.analyzer.workflow;

import com.aijob.analyzer.workflow.domain.StageSignal;
import com.aijob.analyzer.workflow.domain.StageType;
import com.aijob.analyzer.workflow.domain.WorkflowStatus;

public class DefaultTransitionPolicy implements TransitionPolicy {

    @Override
    public TransitionDecision next(StageType current, StageResult result, AnalysisContext context) {
        StageSignal signal = result.signal();

        if (signal == StageSignal.FAILED) {
            return new TransitionDecision.Terminal(WorkflowStatus.FAILED);
        }

        return switch (current) {
            case RULE_CHECK -> handleRuleCheck(signal);
            case PROFILE_RETRIEVAL -> handleRetrieval(signal);
            case LLM_ANALYSIS -> handleLlmAnalysis(signal);
            case RESULT_VALIDATE -> handleValidate(signal);
            case COMMIT_RESULT -> handleCommit(signal);
            default -> new TransitionDecision.Terminal(WorkflowStatus.FAILED);
        };
    }

    private TransitionDecision handleRuleCheck(StageSignal signal) {
        return switch (signal) {
            case CONTINUE, SUCCESS -> new TransitionDecision.Continue(StageType.PROFILE_RETRIEVAL);
            case HARD_REJECT -> new TransitionDecision.Terminal(WorkflowStatus.SUCCESS);
            default -> new TransitionDecision.Terminal(WorkflowStatus.FAILED);
        };
    }

    private TransitionDecision handleRetrieval(StageSignal signal) {
        return switch (signal) {
            case SUCCESS, NO_EVIDENCE -> new TransitionDecision.Continue(StageType.LLM_ANALYSIS);
            default -> new TransitionDecision.Terminal(WorkflowStatus.FAILED);
        };
    }

    private TransitionDecision handleLlmAnalysis(StageSignal signal) {
        return switch (signal) {
            case SUCCESS -> new TransitionDecision.Continue(StageType.RESULT_VALIDATE);
            default -> new TransitionDecision.Terminal(WorkflowStatus.FAILED);
        };
    }

    private TransitionDecision handleValidate(StageSignal signal) {
        return switch (signal) {
            case SUCCESS -> new TransitionDecision.Continue(StageType.COMMIT_RESULT);
            default -> new TransitionDecision.Terminal(WorkflowStatus.FAILED);
        };
    }

    private TransitionDecision handleCommit(StageSignal signal) {
        return switch (signal) {
            case SUCCESS -> new TransitionDecision.Terminal(WorkflowStatus.SUCCESS);
            default -> new TransitionDecision.Terminal(WorkflowStatus.FAILED);
        };
    }
}
