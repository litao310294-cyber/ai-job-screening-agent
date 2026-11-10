package com.aijob.analyzer.llm.recovery;

import com.aijob.analyzer.llm.error.ProviderError;
import com.aijob.analyzer.llm.error.ProviderTransportException;
import com.aijob.analyzer.llm.validation.BusinessValidationError;
import com.aijob.analyzer.llm.validation.GroundingError;
import com.aijob.analyzer.llm.validation.JsonParseException;
import com.aijob.analyzer.llm.validation.SchemaValidationError;
import org.springframework.stereotype.Component;

@Component
public class LlmErrorClassifier {

    public RecoveryAction classify(Throwable throwable, int attempt, int repairCount) {
        if (throwable instanceof ProviderTransportException) {
            return RecoveryAction.RETRY;
        }

        if (throwable instanceof ProviderError pe) {
            int status = pe.getStatusCode();
            if (status == 429 || status == 503 || status == 504) {
                return RecoveryAction.RETRY;
            }
            return RecoveryAction.FAIL;
        }

        if (throwable instanceof JsonParseException || throwable instanceof SchemaValidationError) {
            // Deterministic formatting normalization has already run before parsing.
            // A remaining parse/schema failure must not trigger a heuristic repair.
            return RecoveryAction.FAIL;
        }

        if (throwable instanceof BusinessValidationError || throwable instanceof GroundingError) {
            return RecoveryAction.FAIL;
        }

        return RecoveryAction.FAIL;
    }
}
