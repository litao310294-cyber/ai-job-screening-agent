package com.aijob.analyzer.analysis.validation;

import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.llm.validation.BusinessValidationError;
import com.aijob.analyzer.llm.validation.GroundingError;
import com.aijob.analyzer.workflow.AnalysisContext;
import com.aijob.analyzer.workflow.domain.ErrorCategory;
import com.aijob.analyzer.workflow.domain.WorkflowError;
import com.aijob.analyzer.workflow.port.ResultValidator;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class JobAnalysisResultValidatorAdapter implements ResultValidator {

    private final JobAnalysisResultValidator validator;

    public JobAnalysisResultValidatorAdapter(JobAnalysisResultValidator validator) {
        this.validator = validator;
    }

    @Override
    public Optional<WorkflowError> validate(JobAnalysisResult candidate, AnalysisContext context) {
        try {
            validator.validate(candidate, context.requirementSet(), context.retrievalResult());
            return Optional.empty();
        } catch (BusinessValidationError e) {
            return Optional.of(new WorkflowError("BUSINESS_VALIDATION_FAILED", ErrorCategory.BUSINESS, false, e.getMessage()));
        } catch (GroundingError e) {
            return Optional.of(new WorkflowError("GROUNDING_FAILED", ErrorCategory.GROUNDING, false, e.getMessage()));
        } catch (Exception e) {
            return Optional.of(new WorkflowError("INTERNAL_ERROR", ErrorCategory.INTERNAL, false, e.getMessage()));
        }
    }
}
