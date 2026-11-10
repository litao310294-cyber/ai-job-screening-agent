package com.aijob.analyzer.workflow;

import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.analysis.domain.JobDecision;
import com.aijob.analyzer.analysis.identity.AnalysisDependencySnapshot;
import com.aijob.analyzer.analysis.identity.AnalysisIdentity;
import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.profile.domain.ProfileFact;
import com.aijob.analyzer.profile.domain.StructuredFact;
import com.aijob.analyzer.requirement.domain.RequirementSet;
import com.aijob.analyzer.retrieval.domain.RetrievalResult;
import com.aijob.analyzer.support.time.ExecutionDeadline;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceRecorder;
import com.aijob.analyzer.workflow.domain.*;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AnalysisContext {
    private final String traceId;
    private final String taskId;
    private final TraceContext traceContext;
    private final TraceRecorder traceRecorder;
    private final JobContext jobContext;
    private final CandidateProfile candidateProfile;
    private final CandidateProfile ruleCandidateProfile;
    private final ProfileArtifactIdentity profileIdentity;
    private final AnalysisIdentity analysisIdentity;
    private final ExecutionDeadline deadline;
    private final AnalysisDependencySnapshot dependencySnapshot;

    private RuleResult ruleResult;
    private RuleResult frozenRuleResult;
    public void setFrozenRuleResult(RuleResult result) { this.frozenRuleResult=result; }
    public RuleResult frozenRuleResult() { return frozenRuleResult; }
    private RequirementSet requirementSet;
    private RetrievalResult retrievalResult;
    private JobAnalysisResult rawResult;
    private JobAnalysisResult validatedResult;
    private WorkflowError lastError;
    private StageType currentStage;
    private WorkflowStatus workflowStatus = WorkflowStatus.RUNNING;
    private JobDecision jobDecision;
    private ResultOrigin resultOrigin;
    private final List<StageAttemptState> attemptHistory = new ArrayList<>();
    private final Map<StageType, StageExecutionStatus> stageStatuses = new EnumMap<>(StageType.class);

    public AnalysisContext(
            String traceId,
            String taskId,
            JobContext jobContext,
            CandidateProfile candidateProfile,
            ProfileArtifactIdentity profileIdentity,
            AnalysisIdentity analysisIdentity,
            ExecutionDeadline deadline,
            AnalysisDependencySnapshot dependencySnapshot) {
        this(traceId, taskId, jobContext, candidateProfile, profileIdentity, analysisIdentity, deadline, dependencySnapshot, null);
    }

    public AnalysisContext(
            String traceId,
            String taskId,
            JobContext jobContext,
            CandidateProfile candidateProfile,
            ProfileArtifactIdentity profileIdentity,
            AnalysisIdentity analysisIdentity,
            ExecutionDeadline deadline,
            AnalysisDependencySnapshot dependencySnapshot,
            TraceRecorder traceRecorder) {
        this.traceId = Objects.requireNonNull(traceId, "traceId");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.traceContext = new TraceContext(traceId, taskId);
        this.traceRecorder = traceRecorder;
        this.jobContext = Objects.requireNonNull(jobContext, "jobContext");
        Objects.requireNonNull(candidateProfile, "candidateProfile");
        this.profileIdentity = Objects.requireNonNull(profileIdentity, "profileIdentity");
        if (!profileIdentity.equals(candidateProfile.identity())) {
            throw new IllegalArgumentException("profileIdentity must match CandidateProfile identity");
        }

        this.candidateProfile = new CandidateProfile(
                candidateProfile.artifactId(),
                candidateProfile.identity(),
                candidateProfile.resumeFingerprint(),
                candidateProfile.parserDependencyFingerprint(),
                candidateProfile.status(),
                List.copyOf(candidateProfile.structuredFacts()),
                List.copyOf(candidateProfile.retrievalFacts()),
                List.copyOf(candidateProfile.contextOnly())
        );
        this.ruleCandidateProfile = new CandidateProfile(
                this.candidateProfile.artifactId(),
                this.candidateProfile.identity(),
                this.candidateProfile.resumeFingerprint(),
                this.candidateProfile.parserDependencyFingerprint(),
                this.candidateProfile.status(),
                this.candidateProfile.structuredFacts(),
                List.of(),
                List.of()
        );
        this.analysisIdentity = Objects.requireNonNull(analysisIdentity, "analysisIdentity");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.dependencySnapshot = Objects.requireNonNull(dependencySnapshot, "dependencySnapshot");
        for (StageType stage : StageType.values()) {
            stageStatuses.put(stage, StageExecutionStatus.NOT_STARTED);
        }
    }

    public String traceId() { return traceId; }
    public String taskId() { return taskId; }
    public TraceContext traceContext() { return traceContext; }
    public TraceRecorder traceRecorder() { return traceRecorder; }
    public JobContext jobContext() { return jobContext; }
    public CandidateProfile ruleCandidateProfile() { return ruleCandidateProfile; }
    public List<StructuredFact> structuredFacts() { return candidateProfile.structuredFacts(); }
    public List<ProfileFact> retrievalFacts() { return candidateProfile.retrievalFacts(); }
    public ProfileArtifactIdentity profileIdentity() { return profileIdentity; }
    public AnalysisIdentity analysisIdentity() { return analysisIdentity; }
    public ExecutionDeadline deadline() { return deadline; }
    public AnalysisDependencySnapshot dependencySnapshot() { return dependencySnapshot; }

    public WorkflowStatus workflowStatus() { return workflowStatus; }
    public void setWorkflowStatus(WorkflowStatus workflowStatus) { this.workflowStatus = workflowStatus; }

    public JobDecision jobDecision() { return jobDecision; }
    public void setJobDecision(JobDecision jobDecision) { this.jobDecision = jobDecision; }

    public ResultOrigin resultOrigin() { return resultOrigin; }
    public void setResultOrigin(ResultOrigin resultOrigin) { this.resultOrigin = resultOrigin; }

    public StageType currentStage() { return currentStage; }
    public void setCurrentStage(StageType currentStage) { this.currentStage = currentStage; }

    public RuleResult ruleResult() { return ruleResult; }
    public void setRuleResult(RuleResult ruleResult) { this.ruleResult = ruleResult; }

    public RequirementSet requirementSet() { return requirementSet; }
    public void setRequirementSet(RequirementSet requirementSet) { this.requirementSet = requirementSet; }

    public RetrievalResult retrievalResult() { return retrievalResult; }
    public void setRetrievalResult(RetrievalResult retrievalResult) { this.retrievalResult = retrievalResult; }

    public JobAnalysisResult rawResult() { return rawResult; }
    public void setRawResult(JobAnalysisResult rawResult) { this.rawResult = rawResult; }

    public JobAnalysisResult validatedResult() { return validatedResult; }
    public void setValidatedResult(JobAnalysisResult validatedResult) { this.validatedResult = validatedResult; }

    public WorkflowError lastError() { return lastError; }
    public void setLastError(WorkflowError lastError) { this.lastError = lastError; }

    public void addAttempt(StageAttemptState attempt) {
        this.attemptHistory.add(attempt);
    }

    public List<StageAttemptState> attemptHistory() {
        return List.copyOf(attemptHistory);
    }

    public StageExecutionStatus stageStatus(StageType stage) {
        return stageStatuses.get(Objects.requireNonNull(stage, "stage"));
    }

    public void setStageStatus(StageType stage, StageExecutionStatus status) {
        stageStatuses.put(
                Objects.requireNonNull(stage, "stage"),
                Objects.requireNonNull(status, "status")
        );
    }

    public Map<StageType, StageExecutionStatus> stageStatuses() {
        return Map.copyOf(stageStatuses);
    }
}
