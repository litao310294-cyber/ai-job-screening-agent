package com.aijob.analyzer.llm.recovery;

import org.springframework.stereotype.Component;

@Component
public class LlmRecoveryPolicy {

    private final LlmErrorClassifier classifier;
    private static final int MAX_ATTEMPTS = 3;

    public LlmRecoveryPolicy(LlmErrorClassifier classifier) {
        this.classifier = classifier;
    }

    public RecoveryAction decide(LlmServiceRole role, Throwable throwable, int attempt, int repairCount) {
        RecoveryAction action = classifier.classify(throwable, attempt, repairCount);

        if (action == RecoveryAction.RETRY && attempt >= MAX_ATTEMPTS) {
            if (role == LlmServiceRole.FINAL_ANALYSIS) {
                return RecoveryAction.FALLBACK;
            }
            return RecoveryAction.FAIL;
        }

        return action;
    }
}
