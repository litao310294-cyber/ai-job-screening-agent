package com.aijob.analyzer.eval;

import com.aijob.analyzer.analysis.domain.AnalysisArtifact;
import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.analysis.domain.JobDecision;
import com.aijob.analyzer.analysis.domain.RequirementAssessment;
import com.aijob.analyzer.analysis.domain.RequirementAssessmentStatus;
import com.aijob.analyzer.analysis.identity.AnalysisDependencySnapshot;
import com.aijob.analyzer.analysis.identity.AnalysisIdentity;
import com.aijob.analyzer.analysis.identity.AnalysisIdentityFactory;
import com.aijob.analyzer.analysis.identity.JobFingerprint;
import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.analysis.identity.RequirementIdentity;
import com.aijob.analyzer.analysis.identity.RequirementIdentityFactory;
import com.aijob.analyzer.analysis.port.AnalysisResultRepository;
import com.aijob.analyzer.cache.port.ArtifactCache;
import com.aijob.analyzer.llm.LlmSemanticVersions;
import com.aijob.analyzer.profile.CurrentProfileResolver;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.profile.domain.ProfileStatus;
import com.aijob.analyzer.requirement.domain.AtomicRequirement;
import com.aijob.analyzer.requirement.domain.RequirementPriority;
import com.aijob.analyzer.requirement.domain.RequirementSet;
import com.aijob.analyzer.retrieval.RetrievalSemanticSnapshot;
import com.aijob.analyzer.retrieval.domain.Evidence;
import com.aijob.analyzer.retrieval.domain.RetrievalResult;
import com.aijob.analyzer.support.FixtureFactory;
import com.aijob.analyzer.support.InMemoryTraceRecorder;
import com.aijob.analyzer.trace.TraceRecorder;
import com.aijob.analyzer.workflow.AnalysisOrchestrator;
import com.aijob.analyzer.workflow.AnalysisStage;
import com.aijob.analyzer.workflow.DefaultFallbackResultFactory;
import com.aijob.analyzer.workflow.DefaultRuleTerminalResultFactory;
import com.aijob.analyzer.workflow.DefaultTransitionPolicy;
import com.aijob.analyzer.workflow.StageResult;
import com.aijob.analyzer.workflow.domain.StageType;
import com.aijob.analyzer.support.retry.DefaultRetryExecutor;
import com.aijob.analyzer.support.retry.RetryPolicy;
import com.aijob.analyzer.support.time.ExecutionDeadline;
import com.aijob.analyzer.support.time.TimeSource;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Stable, provider-free fixtures shared by the offline evidence producers. */
final class OfflineEvidenceFixtures {
    private OfflineEvidenceFixtures() {}

    static LlmSemanticVersions versions() {
        LlmSemanticVersions versions = new LlmSemanticVersions();
        LlmSemanticVersions.NodeVersions node = new LlmSemanticVersions.NodeVersions();
        node.setPrompt("offline-prompt-v1");
        node.setModel("offline-model");
        node.setSchema("job-analysis-v1");
        node.setTemperature(java.math.BigDecimal.ZERO);
        node.setTopP(java.math.BigDecimal.ONE);
        node.setMaxOutputTokens(4000);
        node.setResponseFormat("json_object");
        versions.setResumeParser(node);
        versions.setRequirementParser(node);
        versions.setFinalAnalysis(node);
        return versions;
    }

    static RetrievalSemanticSnapshot retrievalSnapshot() {
        return new RetrievalSemanticSnapshot("query-builder-v1", "lucene-standard", "lucene-standard-v1",
                "offline-embedding", 20, 20, 60, 5);
    }

    static CandidateProfile profile(String userId, long version) {
        return new CandidateProfile(version, new ProfileArtifactIdentity(userId, version),
                "resume-fingerprint", "parser-fingerprint", ProfileStatus.READY,
                List.of(), List.of(), List.of());
    }

    static JobContext job() {
        return new JobContext("offline", "job-001", "Java Backend Intern", "Shanghai", 3,
                "3 months", "面议", "Java Spring Boot Redis backend internship", new JobFingerprint("job-fingerprint-001"));
    }

    static RequirementSet requirementSet(boolean withEvidence) {
        RequirementIdentity identity = new RequirementIdentity(job().jobFingerprint(),
                new com.aijob.analyzer.analysis.identity.ParserDependencyFingerprint("requirement-parser-v1"));
        AtomicRequirement requirement = new AtomicRequirement("R0001", "skill", "Java and Spring Boot",
                RequirementPriority.REQUIRED, "Java and Spring Boot required");
        return new RequirementSet(101L, identity, List.of(), List.of(requirement), List.of());
    }

    static RetrievalResult retrievalResult(boolean withEvidence) {
        if (!withEvidence) return new RetrievalResult(Map.of("R0001", List.of()));
        Evidence evidence = new Evidence("F0001", "R0001", "F0001", "Candidate used Java and Spring Boot",
                new com.aijob.analyzer.profile.domain.SourceReference("resume-1", "Resume", "Projects", "Java and Spring Boot"));
        return new RetrievalResult(Map.of("R0001", List.of(evidence)));
    }

    static JobAnalysisResult validResult(boolean withEvidence) {
        List<RequirementAssessment> assessments = withEvidence
                ? List.of(new RequirementAssessment("R0001", RequirementAssessmentStatus.SUPPORTED,
                List.of("F0001"), "Evidence supports the requirement", ""))
                : List.of(new RequirementAssessment("R0001", RequirementAssessmentStatus.INSUFFICIENT_EVIDENCE,
                List.of(), "No bound evidence", "Need more evidence"));
        return new JobAnalysisResult(withEvidence ? JobDecision.RECOMMEND_APPLY : JobDecision.INSUFFICIENT_EVIDENCE,
                "offline assessment", assessments, List.of(), List.of(), "review", "offline summary");
    }

    static AnalysisDependencySnapshot snapshot(CandidateProfile profile, RequirementSet requirements,
                                               AnalysisIdentityFactory identityFactory,
                                               LlmSemanticVersions versions) {
        RetrievalSemanticSnapshot retrieval = retrievalSnapshot();
        return new AnalysisDependencySnapshot(job().jobFingerprint(), profile.artifactId(), profile.identity(),
                requirements.artifactId(), requirements.identity(), "RULE-V1",
                identityFactory.retrievalFingerprint(retrieval), retrieval,
                identityFactory.finalLlmFingerprint(versions.getFinalAnalysisSnapshot()),
                versions.getFinalAnalysisSnapshot());
    }

    static AnalysisIdentity identity(CandidateProfile profile, RequirementSet requirements,
                                     AnalysisIdentityFactory identityFactory, LlmSemanticVersions versions) {
        return identityFactory.create(snapshot(profile, requirements, identityFactory, versions));
    }

    static AnalysisIdentity applicationIdentity(CandidateProfile profile, AnalysisIdentityFactory identityFactory,
                                                LlmSemanticVersions versions) {
        RequirementIdentity requirementIdentity = new RequirementIdentityFactory()
                .create(job().jobFingerprint(), versions.getRequirementParserSnapshot());
        AnalysisDependencySnapshot snapshot = new AnalysisDependencySnapshot(
                job().jobFingerprint(), profile.artifactId(), profile.identity(), 0L, requirementIdentity,
                "RULE-V1", identityFactory.retrievalFingerprint(retrievalSnapshot()),
                retrievalSnapshot(), identityFactory.finalLlmFingerprint(versions.getFinalAnalysisSnapshot()),
                versions.getFinalAnalysisSnapshot());
        return identityFactory.create(snapshot);
    }

    static AnalysisArtifact artifact(AnalysisIdentity identity, AnalysisDependencySnapshot snapshot,
                                     boolean withEvidence) {
        return new AnalysisArtifact(Math.abs(identity.analysisDependencyFingerprint().value().hashCode()) + 1L,
                identity, snapshot, validResult(withEvidence), Instant.parse("2026-01-01T00:00:00Z"));
    }

    static AnalysisOrchestrator simpleOrchestrator(TraceRecorder recorder,
                                                   Function<com.aijob.analyzer.workflow.AnalysisContext, StageResult> llm,
                                                   Function<com.aijob.analyzer.workflow.AnalysisContext, StageResult> commit) {
        Map<StageType, AnalysisStage> stages = new EnumMap<>(StageType.class);
        stages.put(StageType.RULE_CHECK, stage(StageType.RULE_CHECK, c -> {
            c.setRuleResult(new com.aijob.analyzer.workflow.domain.RuleResult(
                    com.aijob.analyzer.workflow.domain.RuleSignal.CONTINUE, List.of()));
            return StageResult.success();
        }));
        stages.put(StageType.PROFILE_RETRIEVAL, stage(StageType.PROFILE_RETRIEVAL, c -> {
            c.setRequirementSet(requirementSet(true));
            c.setRetrievalResult(retrievalResult(true));
            return StageResult.success();
        }));
        stages.put(StageType.LLM_ANALYSIS, stage(StageType.LLM_ANALYSIS, llm));
        stages.put(StageType.RESULT_VALIDATE, stage(StageType.RESULT_VALIDATE, c -> {
            c.setValidatedResult(c.rawResult() == null ? validResult(true) : c.rawResult());
            return StageResult.success();
        }));
        stages.put(StageType.COMMIT_RESULT, stage(StageType.COMMIT_RESULT, commit));
        Map<StageType, RetryPolicy> policies = new EnumMap<>(StageType.class);
        for (StageType type : StageType.values()) {
            policies.put(type, new RetryPolicy(type == StageType.LLM_ANALYSIS ? 3 : 1,
                    Duration.ZERO, 1.0, Duration.ZERO, 0.0));
        }
        return new AnalysisOrchestrator(stages, new DefaultTransitionPolicy(),
                new DefaultRuleTerminalResultFactory(), new DefaultFallbackResultFactory(), recorder,
                new DefaultRetryExecutor(duration -> {}, () -> 0.0), policies);
    }

    static AnalysisStage stage(StageType type, Function<com.aijob.analyzer.workflow.AnalysisContext, StageResult> operation) {
        return new AnalysisStage() {
            @Override public StageType type() { return type; }
            @Override public StageResult execute(com.aijob.analyzer.workflow.AnalysisContext context) { return operation.apply(context); }
        };
    }

    static com.aijob.analyzer.workflow.AnalysisContext context(String traceId, String taskId) {
        return context(traceId, taskId, null);
    }

    static com.aijob.analyzer.workflow.AnalysisContext context(String traceId, String taskId, TraceRecorder recorder) {
        AnalysisIdentityFactory identityFactory = new AnalysisIdentityFactory();
        LlmSemanticVersions versions = versions();
        CandidateProfile profile = profile("offline-user", 1L);
        RequirementSet requirements = requirementSet(true);
        AnalysisDependencySnapshot snapshot = snapshot(profile, requirements, identityFactory, versions);
        return new com.aijob.analyzer.workflow.AnalysisContext(traceId, taskId, job(), profile, profile.identity(),
                identityFactory.create(snapshot), ExecutionDeadline.fromNow(Duration.ofSeconds(30), TimeSource.SYSTEM), snapshot, recorder);
    }

    static String validJson(boolean withEvidence) {
        return withEvidence
                ? "{\"decision\":\"RECOMMEND_APPLY\",\"overallAssessment\":\"ok\",\"requirementAssessments\":[{\"requirementId\":\"R0001\",\"status\":\"SUPPORTED\",\"evidenceRefs\":[\"F0001\"],\"reasoning\":\"evidence\",\"gap\":\"\"}],\"risks\":[],\"strengths\":[],\"recommendation\":\"apply\",\"summary\":\"ok\"}"
                : "{\"decision\":\"INSUFFICIENT_EVIDENCE\",\"overallAssessment\":\"none\",\"requirementAssessments\":[{\"requirementId\":\"R0001\",\"status\":\"INSUFFICIENT_EVIDENCE\",\"evidenceRefs\":[],\"reasoning\":\"none\",\"gap\":\"missing\"}],\"risks\":[],\"strengths\":[],\"recommendation\":\"review\",\"summary\":\"none\"}";
    }

    static Map<String, Object> safe(String value) {
        return Map.of("safeId", value == null ? "null" : value.substring(0, Math.min(12, value.length())));
    }
}
