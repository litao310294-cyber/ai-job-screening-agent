package com.aijob.analyzer.llm.recovery;

import com.aijob.analyzer.llm.error.ProviderError;
import com.aijob.analyzer.llm.error.ProviderTransportException;
import com.aijob.analyzer.llm.validation.GroundingError;
import com.aijob.analyzer.llm.validation.JsonParseException;
import com.aijob.analyzer.llm.validation.SchemaValidationError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmRecoveryPolicyTest {

    private final LlmErrorClassifier classifier = new LlmErrorClassifier();
    private final LlmRecoveryPolicy policy = new LlmRecoveryPolicy(classifier);

    @Test
    @DisplayName("AC-LLM-08: Timeout should be retryable")
    void testTimeoutRetryable() {
        Throwable t = new ProviderTransportException("timeout", new RuntimeException());
        assertEquals(RecoveryAction.RETRY, policy.decide(LlmServiceRole.FINAL_ANALYSIS, t, 1, 0));
    }

    @Test
    @DisplayName("AC-LLM-06: 429 should be retryable")
    void test429Retryable() {
        Throwable t = new ProviderError(429, "TOO_MANY_REQUESTS", "Rate limit");
        assertEquals(RecoveryAction.RETRY, policy.decide(LlmServiceRole.FINAL_ANALYSIS, t, 1, 0));
    }

    @Test
    @DisplayName("AC-LLM-09: 401 should fail")
    void test401Fail() {
        Throwable t = new ProviderError(401, "UNAUTHORIZED", "Invalid key");
        assertEquals(RecoveryAction.FAIL, policy.decide(LlmServiceRole.FINAL_ANALYSIS, t, 1, 0));
    }

    @Test
    @DisplayName("AC-LLM-01: Malformed JSON is not heuristically repairable")
    void testMalformedJsonIsNotHeuristicallyRepairable() {
        Throwable t = new JsonParseException("bad json", null);
        assertEquals(RecoveryAction.FAIL, policy.decide(LlmServiceRole.FINAL_ANALYSIS, t, 1, 0));
    }

    @Test
    @DisplayName("AC-LLM-01: Malformed JSON remains failed after deterministic normalization")
    void testMalformedJsonRemainsFailedAfterDeterministicNormalization() {
        Throwable t = new JsonParseException("bad json", null);
        assertEquals(RecoveryAction.FAIL, policy.decide(LlmServiceRole.FINAL_ANALYSIS, t, 1, 1));
    }

    @Test
    @DisplayName("AC-LLM-03: Schema errors are not heuristically repairable")
    void testSchemaErrorIsNotHeuristicallyRepairable() {
        Throwable t = new SchemaValidationError("unknown enum");
        assertEquals(RecoveryAction.FAIL, policy.decide(LlmServiceRole.FINAL_ANALYSIS, t, 1, 0));
    }

    @Test
    @DisplayName("AC-LLM-05: Grounding error should fail")
    void testGroundingErrorFail() {
        Throwable t = new GroundingError("unknown ref");
        assertEquals(RecoveryAction.FAIL, policy.decide(LlmServiceRole.FINAL_ANALYSIS, t, 1, 0));
    }

    @Test
    @DisplayName("AC-LLM-10: Final Analysis exhausted should fallback")
    void testFinalAnalysisExhaustedFallback() {
        Throwable t = new ProviderTransportException("timeout", null);
        assertEquals(RecoveryAction.FALLBACK, policy.decide(LlmServiceRole.FINAL_ANALYSIS, t, 3, 0));
    }

    @Test
    @DisplayName("AC-LLM-11: Resume Parser exhausted should fail")
    void testResumeParserExhaustedFail() {
        Throwable t = new ProviderTransportException("timeout", null);
        assertEquals(RecoveryAction.FAIL, policy.decide(LlmServiceRole.RESUME_PARSER, t, 3, 0));
    }
}
