package com.aijob.analyzer.workflow.stage;

import com.aijob.analyzer.llm.LlmSemanticVersions;
import com.aijob.analyzer.requirement.RequirementResolutionService;
import com.aijob.analyzer.requirement.domain.RequirementSet;
import com.aijob.analyzer.retrieval.ProfileRetrievalService;
import com.aijob.analyzer.retrieval.domain.RetrievalResult;
import com.aijob.analyzer.workflow.AnalysisContext;
import com.aijob.analyzer.workflow.AnalysisStage;
import com.aijob.analyzer.workflow.StageResult;
import com.aijob.analyzer.workflow.domain.StageType;

public class RetrievalStage implements AnalysisStage {

    private final ProfileRetrievalService retrievalService;
    private final RequirementResolutionService requirementResolutionService;
    private final LlmSemanticVersions versions;

    public RetrievalStage(
            ProfileRetrievalService retrievalService,
            RequirementResolutionService requirementResolutionService,
            LlmSemanticVersions versions) {
        this.retrievalService = retrievalService;
        this.requirementResolutionService = requirementResolutionService;
        this.versions = versions;
    }

    @Override
    public StageType type() {
        return StageType.PROFILE_RETRIEVAL;
    }

    @Override
    public StageResult execute(AnalysisContext context) {
        // 1. Resolve requirements if not already present
        if (context.requirementSet() == null) {
            RequirementSet requirementSet = requirementResolutionService.resolve(
                    context.jobContext(),
                    context.dependencySnapshot().requirementSetIdentity(),
                    versions.getRequirementParserSnapshot(),
                    context.traceContext(),
                    context.traceRecorder()
            );
            context.setRequirementSet(requirementSet);
        }

        if (context.requirementSet().retrievalRequirements().isEmpty()) {
            context.setRetrievalResult(new RetrievalResult(java.util.Map.of()));
            return StageResult.success();
        }

        // 2. Perform retrieval
        RetrievalResult result = retrievalService.retrieve(
                context.profileIdentity(),
                context.requirementSet(),
                context.traceContext(),
                context.traceRecorder()
        );

        context.setRetrievalResult(result);

        // RT-12 Signal logic
        boolean hasAnyEvidence = result.evidenceByRequirement().values().stream()
                .anyMatch(evList -> !evList.isEmpty());

        if (!hasAnyEvidence) {
            return StageResult.noEvidence();
        }

        return StageResult.success();
    }
}
