package com.aijob.analyzer.workflow;

import com.aijob.analyzer.workflow.domain.ErrorCategory;
import com.aijob.analyzer.workflow.domain.StageType;
import com.aijob.analyzer.workflow.domain.WorkflowError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultFallbackPolicyTest {

    private final DefaultFallbackPolicy policy = new DefaultFallbackPolicy();

    @Test
    void allowsOnlyRetryableFinalAnalysisExhaustion() {
        assertTrue(policy.allows(
                StageType.LLM_ANALYSIS,
                new WorkflowError("TIMEOUT", ErrorCategory.TRANSPORT, true, "timeout")
        ));
        assertTrue(policy.allows(
                StageType.LLM_ANALYSIS,
                new WorkflowError("PROVIDER_503", ErrorCategory.PROVIDER, true, "unavailable")
        ));
    }

    @Test
    void rejectsNonRetryableAndNonProviderFinalAnalysisFailures() {
        assertFalse(policy.allows(
                StageType.LLM_ANALYSIS,
                new WorkflowError("INVALID_JSON", ErrorCategory.PARSE, false, "invalid")
        ));
        assertFalse(policy.allows(
                StageType.LLM_ANALYSIS,
                new WorkflowError("SCHEMA", ErrorCategory.SCHEMA, false, "invalid")
        ));
        assertFalse(policy.allows(
                StageType.LLM_ANALYSIS,
                new WorkflowError("INTERNAL", ErrorCategory.INTERNAL, false, "bug")
        ));
    }
}
