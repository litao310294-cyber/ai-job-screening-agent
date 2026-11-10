package com.aijob.analyzer.workflow;

import com.aijob.analyzer.workflow.domain.StageSignal;
import com.aijob.analyzer.workflow.domain.StageType;
import com.aijob.analyzer.workflow.domain.WorkflowStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransitionPolicyTest {

    private final TransitionPolicy policy = new DefaultTransitionPolicy();

    @Test
    void ruleCheckTransitions() {
        assertContinue(StageType.RULE_CHECK, StageSignal.SUCCESS, StageType.PROFILE_RETRIEVAL);
        assertTerminal(StageType.RULE_CHECK, StageSignal.HARD_REJECT, WorkflowStatus.SUCCESS);
    }

    @Test
    void retrievalTransitions() {
        assertContinue(StageType.PROFILE_RETRIEVAL, StageSignal.SUCCESS, StageType.LLM_ANALYSIS);
        assertContinue(StageType.PROFILE_RETRIEVAL, StageSignal.NO_EVIDENCE, StageType.LLM_ANALYSIS);
    }

    @Test
    void llmAnalysisTransitions() {
        assertContinue(StageType.LLM_ANALYSIS, StageSignal.SUCCESS, StageType.RESULT_VALIDATE);
    }

    @Test
    void validateTransitions() {
        assertContinue(StageType.RESULT_VALIDATE, StageSignal.SUCCESS, StageType.COMMIT_RESULT);
    }

    @Test
    void commitTransitions() {
        assertTerminal(StageType.COMMIT_RESULT, StageSignal.SUCCESS, WorkflowStatus.SUCCESS);
    }

    private void assertContinue(StageType current, StageSignal signal, StageType expectedNext) {
        var decision = policy.next(current, new StageResult(signal, null), null);
        assertTrue(decision instanceof TransitionDecision.Continue);
        assertEquals(expectedNext, ((TransitionDecision.Continue) decision).nextStage());
    }

    private void assertTerminal(StageType current, StageSignal signal, WorkflowStatus expectedStatus) {
        var decision = policy.next(current, new StageResult(signal, null), null);
        assertTrue(decision instanceof TransitionDecision.Terminal);
        assertEquals(expectedStatus, ((TransitionDecision.Terminal) decision).status());
    }
}
