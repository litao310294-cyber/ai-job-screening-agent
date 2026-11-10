package com.aijob.analyzer.workflow.stage;

import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.llm.FinalAnalysisService;
import com.aijob.analyzer.llm.LlmExecutionContext;
import com.aijob.analyzer.llm.LlmSemanticVersions;
import com.aijob.analyzer.llm.recovery.LlmRecoveryPolicy;
import com.aijob.analyzer.llm.recovery.LlmServiceRole;
import com.aijob.analyzer.llm.recovery.RecoveryAction;
import com.aijob.analyzer.workflow.AnalysisContext;
import com.aijob.analyzer.workflow.AnalysisStage;
import com.aijob.analyzer.workflow.StageResult;
import com.aijob.analyzer.workflow.domain.ErrorCategory;
import com.aijob.analyzer.workflow.domain.StageType;
import com.aijob.analyzer.workflow.domain.WorkflowError;

import java.util.Objects;

public final class LlmAnalysisStage implements AnalysisStage {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LlmAnalysisStage.class);

    private final FinalAnalysisService analysisService;
    private final LlmSemanticVersions versions;
    private final LlmRecoveryPolicy recoveryPolicy;

    public LlmAnalysisStage(
            FinalAnalysisService analysisService,
            LlmSemanticVersions versions,
            LlmRecoveryPolicy recoveryPolicy) {
        this.analysisService = Objects.requireNonNull(analysisService, "analysisService");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.recoveryPolicy = Objects.requireNonNull(recoveryPolicy, "recoveryPolicy");
    }

    @Override
    public StageType type() {
        return StageType.LLM_ANALYSIS;
    }

    @Override
    public StageResult execute(AnalysisContext context) {
        int attempt = (int) context.attemptHistory().stream()
                .filter(a -> a.stageType() == StageType.LLM_ANALYSIS)
                .count() + 1;

        try {
            JobAnalysisResult result = analysisService.generateStructured(
                    context.jobContext(),
                    context.ruleResult(),
                    context.requirementSet(),
                    context.structuredFacts(),
                    context.retrievalResult(),
                    new LlmExecutionContext(versions.getFinalAnalysisSnapshot(), attempt, 0),
                    context.traceContext(),
                    context.traceRecorder()
            );
            context.setValidatedResult(result);
            context.setRawResult(result);
            return StageResult.success();
        } catch (Exception e) {
            if (e instanceof com.aijob.analyzer.llm.validation.BusinessValidationError ||
                    e instanceof com.aijob.analyzer.llm.validation.GroundingError ||
                    e instanceof com.aijob.analyzer.llm.validation.SchemaValidationError) {
                log.warn("Final analysis contract rejected [{}]: {}", mapToCategory(e), e.getMessage());
            }
            RecoveryAction action = recoveryPolicy.decide(LlmServiceRole.FINAL_ANALYSIS, e, attempt, 0);
            // FALLBACK is selected only after the bounded retry budget is exhausted;
            // keep the error retryable so the orchestrator can apply its frozen
            // fallback policy instead of treating the terminal as an internal failure.
            return StageResult.failed(new WorkflowError(
                    "LLM_ANALYSIS_FAILED",
                    mapToCategory(e),
                    action == RecoveryAction.RETRY || action == RecoveryAction.FALLBACK,
                    e.getMessage()
            ));
        }
    }

    private ErrorCategory mapToCategory(Throwable t) {
        if (t instanceof com.aijob.analyzer.llm.error.ProviderTransportException) return ErrorCategory.TRANSPORT;
        if (t instanceof com.aijob.analyzer.llm.error.ProviderError) return ErrorCategory.PROVIDER;
        if (t instanceof com.aijob.analyzer.llm.validation.JsonParseException) return ErrorCategory.PARSE;
        if (t instanceof com.aijob.analyzer.llm.validation.SchemaValidationError) return ErrorCategory.SCHEMA;
        if (t instanceof com.aijob.analyzer.llm.validation.BusinessValidationError) return ErrorCategory.BUSINESS;
        if (t instanceof com.aijob.analyzer.llm.validation.GroundingError) return ErrorCategory.GROUNDING;
        return ErrorCategory.INTERNAL;
    }
}
