package com.aijob.analyzer.e2e;

import com.aijob.analyzer.analysis.AnalysisApplicationService;
import com.aijob.analyzer.analysis.concurrency.JvmSingleFlightCoordinator;
import com.aijob.analyzer.analysis.domain.AnalysisArtifact;
import com.aijob.analyzer.analysis.domain.JobDecision;
import com.aijob.analyzer.analysis.identity.AnalysisIdentity;
import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.profile.domain.ProfileStatus;
import com.aijob.analyzer.analysis.port.AnalysisResultRepository;
import com.aijob.analyzer.cache.model.CacheAccessStatus;
import com.aijob.analyzer.cache.model.CacheLookupOutcome;
import com.aijob.analyzer.cache.model.CacheReadResult;
import com.aijob.analyzer.cache.port.ArtifactCache;
import com.aijob.analyzer.profile.CurrentProfileResolver;
import com.aijob.analyzer.support.FixtureFactory;
import com.aijob.analyzer.support.InMemoryTraceRecorder;
import com.aijob.analyzer.trace.TraceRecorder;
import com.aijob.analyzer.workflow.AnalysisOrchestrator;
import com.aijob.analyzer.workflow.AnalysisStage;
import com.aijob.analyzer.workflow.DefaultRuleTerminalResultFactory;
import com.aijob.analyzer.workflow.DefaultTransitionPolicy;
import com.aijob.analyzer.workflow.FallbackResultFactory;
import com.aijob.analyzer.workflow.StageResult;
import com.aijob.analyzer.workflow.domain.RuleResult;
import com.aijob.analyzer.workflow.domain.RuleSignal;
import com.aijob.analyzer.workflow.domain.StageSignal;
import com.aijob.analyzer.workflow.domain.StageType;
import com.aijob.analyzer.requirement.domain.RequirementSet;
import com.aijob.analyzer.retrieval.domain.RetrievalResult;
import com.aijob.analyzer.trace.TraceEventType;
import com.aijob.analyzer.trace.TraceRecord;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.workflow.AnalysisContext;
import com.aijob.analyzer.workflow.stage.RuleCheckStage;
import com.aijob.analyzer.workflow.DefaultFallbackResultFactory;
import com.aijob.analyzer.support.retry.DefaultRetryExecutor;
import com.aijob.analyzer.support.retry.RetryPolicy;
import com.aijob.analyzer.support.retry.RetryExecutor;
import com.aijob.analyzer.support.retry.Sleeper;
import com.aijob.analyzer.support.retry.JitterSource;
import com.aijob.analyzer.workflow.domain.AnalysisExecution;
import com.aijob.analyzer.workflow.domain.ResultOrigin;
import com.aijob.analyzer.workflow.domain.WorkflowStatus;
import com.aijob.analyzer.llm.LlmSemanticVersions;
import com.aijob.analyzer.retrieval.RetrievalSemanticSnapshot;
import com.aijob.analyzer.analysis.identity.AnalysisIdentityFactory;
import com.aijob.analyzer.analysis.identity.RequirementIdentityFactory;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.persistence.model.DbReadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.EnumMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JobAnalysisApplicationE2ETest {
    private AnalysisResultRepository repository;
    private ArtifactCache<AnalysisArtifact> cache;
    private CurrentProfileResolver profileResolver;
    private AnalysisOrchestrator orchestrator;
    private LlmSemanticVersions versions;
    private RetrievalSemanticSnapshot retrieval;

    @BeforeEach
    void setUp() {
        repository = mock(AnalysisResultRepository.class);
        cache = mock(ArtifactCache.class);
        profileResolver = mock(CurrentProfileResolver.class);
        orchestrator = mock(AnalysisOrchestrator.class);
        versions = new LlmSemanticVersions();
        var node = new LlmSemanticVersions.NodeVersions();
        node.setPrompt("prompt"); node.setModel("model"); node.setSchema("schema");
        versions.setResumeParser(node); versions.setRequirementParser(node); versions.setFinalAnalysis(node);
        retrieval = new RetrievalSemanticSnapshot("v1", "lucene", "1.0", "deterministic", 20, 20, 60, 5);
        when(profileResolver.resolveCurrent("user-1")).thenReturn(Optional.of(FixtureFactory.candidateProfile(1L, "user-1")));
    }

    @Test
    void e2e_01_normal_compute_commits_and_returns_artifact() {
        AnalysisArtifact artifact = artifact();
        when(cache.get(any())).thenReturn(new CacheReadResult<>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.MISS, null));
        when(repository.findCanonical(any())).thenReturn(new DbReadResult<>(DbReadOutcome.NOT_FOUND, null, null));
        when(repository.readBack(any())).thenReturn(new DbReadResult<>(DbReadOutcome.FOUND, artifact, null));
        when(orchestrator.execute(any())).thenReturn(new AnalysisExecution(WorkflowStatus.SUCCESS, ResultOrigin.LLM_ANALYSIS, artifact.result(), null));

        AnalysisExecution result = service(new InMemoryTraceRecorder()).analyze("user-1", FixtureFactory.jobContext());

        assertEquals(WorkflowStatus.SUCCESS, result.status());
        verify(orchestrator).execute(any());
        verify(repository).readBack(any());
    }

    @Test
    void e2e_02_database_canonical_reuse_skips_workflow() {
        AnalysisArtifact artifact = artifact();
        when(cache.get(any())).thenReturn(new CacheReadResult<>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.MISS, null));
        when(repository.findCanonical(any())).thenReturn(new DbReadResult<>(DbReadOutcome.FOUND, artifact, null));

        AnalysisExecution result = service(new InMemoryTraceRecorder()).analyze("user-1", FixtureFactory.jobContext());

        assertEquals(WorkflowStatus.SUCCESS, result.status());
        verifyNoInteractions(orchestrator);
        verify(repository, never()).readBack(any());
    }

    @Test
    void e2e_06_db_read_failed_does_not_compute() {
        when(cache.get(any())).thenReturn(new CacheReadResult<>(CacheAccessStatus.UNAVAILABLE, null, null));
        when(repository.findCanonical(any())).thenReturn(new DbReadResult<>(DbReadOutcome.READ_FAILED, null, "DB_DOWN"));

        assertThrows(RuntimeException.class, () -> service(new InMemoryTraceRecorder()).analyze("user-1", FixtureFactory.jobContext()));
        verifyNoInteractions(orchestrator);
    }

    @Test
    void e2e_ac_tr_10_freezes_profile_identity_when_current_pointer_changes() {
        AnalysisArtifact artifact = artifact();
        var p100 = profile(100L);
        var p101 = profile(101L);
        when(profileResolver.resolveCurrent("user-1")).thenReturn(Optional.of(p100), Optional.of(p101));
        when(cache.get(any())).thenReturn(new CacheReadResult<>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.MISS, null));
        when(repository.findCanonical(any())).thenReturn(new DbReadResult<>(DbReadOutcome.NOT_FOUND, null, null));
        when(repository.readBack(any())).thenReturn(new DbReadResult<>(DbReadOutcome.FOUND, artifact, null));
        InMemoryTraceRecorder trace = new InMemoryTraceRecorder();
        java.util.concurrent.atomic.AtomicLong retrievalProfile = new java.util.concurrent.atomic.AtomicLong();
        AnalysisOrchestrator workflow = workflow(trace, new RuleResult(RuleSignal.CONTINUE, List.of()),
                context -> { retrievalProfile.set(context.profileIdentity().profileVersion()); profileResolver.resolveCurrent("user-1"); context.setRequirementSet(FixtureFactory.requirementSet(1)); context.setRetrievalResult(new RetrievalResult(Map.of())); return StageResult.noEvidence(); },
                context -> { context.setValidatedResult(FixtureFactory.analysisResult()); return StageResult.success(); });

        AnalysisExecution execution = service(trace, workflow).analyze("user-1", FixtureFactory.jobContext());

        assertEquals(WorkflowStatus.SUCCESS, execution.status());
        assertEquals(100, retrievalProfile.get());
        assertTrue(trace.getEvents().stream()
                .filter(event -> event.attributes().containsKey("profileVersion"))
                .allMatch(event -> Long.valueOf(100L).equals(event.attributes().get("profileVersion"))));
    }

    @Test
    void e2e_hard_reject_terminates_before_retrieval_llm_and_commit() {
        when(cache.get(any())).thenReturn(new CacheReadResult<>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.MISS, null));
        when(repository.findCanonical(any())).thenReturn(new DbReadResult<>(DbReadOutcome.NOT_FOUND, null, null));
        InMemoryTraceRecorder trace = new InMemoryTraceRecorder();
        AtomicInteger retrieval = new AtomicInteger();
        AtomicInteger llm = new AtomicInteger();
        AnalysisOrchestrator workflow = workflow(trace, new RuleResult(RuleSignal.HARD_REJECT, List.of("location")),
                context -> { retrieval.incrementAndGet(); return StageResult.noEvidence(); },
                context -> { llm.incrementAndGet(); return StageResult.success(); });

        AnalysisExecution execution = service(trace, workflow).analyze("user-1", FixtureFactory.jobContext());

        assertEquals(WorkflowStatus.SUCCESS, execution.status());
        assertEquals(JobDecision.NOT_RECOMMEND, execution.result().decision());
        assertEquals(ResultOrigin.RULE_TERMINAL, execution.resultOrigin());
        assertEquals(0, retrieval.get());
        assertEquals(0, llm.get());
        verify(repository, never()).readBack(any());
        assertTrue(trace.getEvents().stream().anyMatch(event -> event.type() == TraceEventType.TRANSITION));
    }

    @Test
    void e2e_no_evidence_continues_to_final_llm_once() {
        AnalysisArtifact artifact = new AnalysisArtifact(1L, FixtureFactory.analysisIdentity(), FixtureFactory.analysisDependencySnapshot(),
                new com.aijob.analyzer.analysis.domain.JobAnalysisResult(JobDecision.INSUFFICIENT_EVIDENCE, "none", List.of(), List.of(), List.of(), "manual", "none"), Instant.now());
        when(cache.get(any())).thenReturn(new CacheReadResult<>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.MISS, null));
        when(repository.findCanonical(any())).thenReturn(new DbReadResult<>(DbReadOutcome.NOT_FOUND, null, null));
        when(repository.readBack(any())).thenReturn(new DbReadResult<>(DbReadOutcome.FOUND, artifact, null));
        InMemoryTraceRecorder trace = new InMemoryTraceRecorder();
        AtomicInteger llm = new AtomicInteger();
        AnalysisOrchestrator workflow = workflow(trace, new RuleResult(RuleSignal.CONTINUE, List.of()),
                context -> { context.setRequirementSet(FixtureFactory.requirementSet(1)); context.setRetrievalResult(new RetrievalResult(Map.of("req-1", List.of()))); context.traceRecorder().event(context.traceContext(), TraceEventType.TRANSITION, Map.of("phase", "RETRIEVAL_COMPLETED", "requirementCount", 1, "evidenceCount", 0)); return StageResult.noEvidence(); },
                context -> { llm.incrementAndGet(); context.setValidatedResult(new com.aijob.analyzer.analysis.domain.JobAnalysisResult(JobDecision.INSUFFICIENT_EVIDENCE, "none", List.of(), List.of(), List.of(), "manual", "none")); return StageResult.success(); });

        AnalysisExecution execution = service(trace, workflow).analyze("user-1", FixtureFactory.jobContext());

        assertEquals(WorkflowStatus.SUCCESS, execution.status());
        assertEquals(JobDecision.INSUFFICIENT_EVIDENCE, execution.result().decision());
        assertEquals(1, llm.get());
        assertTrue(trace.getEvents().stream().anyMatch(event -> event.type() == TraceEventType.TRANSITION
                && Integer.valueOf(0).equals(event.attributes().get("evidenceCount"))));
    }

    @Test
    void e2e_concurrent_same_identity_shares_one_application_compute() throws Exception {
        AnalysisArtifact artifact = artifact();
        when(cache.get(any())).thenReturn(new CacheReadResult<>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.MISS, null));
        when(repository.findCanonical(any())).thenReturn(new DbReadResult<>(DbReadOutcome.NOT_FOUND, null, null));
        when(repository.readBack(any())).thenReturn(new DbReadResult<>(DbReadOutcome.FOUND, artifact, null));
        InMemoryTraceRecorder trace = new InMemoryTraceRecorder();
        AtomicInteger computes = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AnalysisOrchestrator workflow = workflow(trace, new RuleResult(RuleSignal.CONTINUE, List.of()),
                context -> { computes.incrementAndGet(); entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } context.setRequirementSet(FixtureFactory.requirementSet(1)); context.setRetrievalResult(new RetrievalResult(Map.of())); return StageResult.noEvidence(); },
                context -> { context.setValidatedResult(FixtureFactory.analysisResult()); return StageResult.success(); });
        AnalysisApplicationService application = service(trace, workflow);
        var results = new CopyOnWriteArrayList<AnalysisExecution>();
        Thread first = Thread.startVirtualThread(() -> results.add(application.analyze("user-1", FixtureFactory.jobContext())));
        entered.await();
        Thread second = Thread.startVirtualThread(() -> results.add(application.analyze("user-1", FixtureFactory.jobContext())));
        while (trace.getEvents().stream().noneMatch(event -> event.type() == TraceEventType.SINGLEFLIGHT_FOLLOWER)) Thread.onSpinWait();
        release.countDown();
        first.join(); second.join();

        assertEquals(2, results.size());
        assertEquals(1, computes.get());
        verify(repository, times(1)).readBack(any(AnalysisIdentity.class));
        assertTrue(trace.getEvents().stream().anyMatch(event -> event.type() == TraceEventType.SINGLEFLIGHT_LEADER));
        assertTrue(trace.getEvents().stream().anyMatch(event -> event.type() == TraceEventType.SINGLEFLIGHT_FOLLOWER));
    }

    private AnalysisApplicationService service(TraceRecorder recorder) {
        return new AnalysisApplicationService(new AnalysisIdentityFactory(), new RequirementIdentityFactory(), repository,
                cache, new JvmSingleFlightCoordinator<>(), orchestrator, profileResolver, versions, retrieval, recorder);
    }

    private AnalysisApplicationService service(TraceRecorder recorder, AnalysisOrchestrator workflow) {
        return new AnalysisApplicationService(new AnalysisIdentityFactory(), new RequirementIdentityFactory(), repository,
                cache, new JvmSingleFlightCoordinator<>(), workflow, profileResolver, versions, retrieval, recorder);
    }

    private AnalysisOrchestrator workflow(InMemoryTraceRecorder recorder, RuleResult ruleResult,
                                          java.util.function.Function<AnalysisContext, StageResult> retrievalOperation,
                                          java.util.function.Function<AnalysisContext, StageResult> llmOperation) {
        Map<StageType, AnalysisStage> stages = new EnumMap<>(StageType.class);
        stages.put(StageType.RULE_CHECK, new RuleCheckStage((job, profile) -> ruleResult));
        stages.put(StageType.PROFILE_RETRIEVAL, new FunctionalStage(StageType.PROFILE_RETRIEVAL, retrievalOperation));
        stages.put(StageType.LLM_ANALYSIS, new FunctionalStage(StageType.LLM_ANALYSIS, llmOperation));
        stages.put(StageType.RESULT_VALIDATE, new FunctionalStage(StageType.RESULT_VALIDATE, context -> StageResult.success()));
        stages.put(StageType.COMMIT_RESULT, new FunctionalStage(StageType.COMMIT_RESULT, context -> StageResult.success()));
        Map<StageType, RetryPolicy> policies = new EnumMap<>(StageType.class);
        for (StageType type : StageType.values()) {
            policies.put(type, new RetryPolicy(1, java.time.Duration.ZERO, 1.0, java.time.Duration.ZERO, 0.0));
        }
        RetryExecutor retryExecutor = new DefaultRetryExecutor(duration -> {}, () -> 0.0);
        return new AnalysisOrchestrator(stages, new DefaultTransitionPolicy(), new DefaultRuleTerminalResultFactory(),
                new DefaultFallbackResultFactory(), recorder, retryExecutor, policies);
    }

    private static final class FunctionalStage implements AnalysisStage {
        private final StageType type;
        private final java.util.function.Function<AnalysisContext, StageResult> operation;

        private FunctionalStage(StageType type, java.util.function.Function<AnalysisContext, StageResult> operation) {
            this.type = type;
            this.operation = operation;
        }

        @Override public StageType type() { return type; }
        @Override public StageResult execute(AnalysisContext context) { return operation.apply(context); }
    }

    private AnalysisArtifact artifact() {
        return new AnalysisArtifact(1L, FixtureFactory.analysisIdentity(), FixtureFactory.analysisDependencySnapshot(), FixtureFactory.analysisResult(), Instant.now());
    }

    private CandidateProfile profile(long version) {
        return new CandidateProfile(version, new ProfileArtifactIdentity("user-1", version), "resume-fp", "parser-fp",
                ProfileStatus.READY, List.of(), List.of(), List.of());
    }
}
