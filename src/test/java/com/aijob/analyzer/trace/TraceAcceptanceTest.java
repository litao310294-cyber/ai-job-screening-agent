package com.aijob.analyzer.trace;

import com.aijob.analyzer.analysis.AnalysisApplicationService;
import com.aijob.analyzer.analysis.concurrency.JvmSingleFlightCoordinator;
import com.aijob.analyzer.analysis.domain.AnalysisArtifact;
import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.analysis.identity.AnalysisIdentity;
import com.aijob.analyzer.analysis.identity.AnalysisIdentityFactory;
import com.aijob.analyzer.analysis.identity.FingerprintService;
import com.aijob.analyzer.analysis.identity.RequirementIdentityFactory;
import com.aijob.analyzer.analysis.port.AnalysisResultRepository;
import com.aijob.analyzer.cache.model.CacheAccessStatus;
import com.aijob.analyzer.cache.model.CacheLookupOutcome;
import com.aijob.analyzer.cache.model.CacheReadResult;
import com.aijob.analyzer.cache.port.ArtifactCache;
import com.aijob.analyzer.llm.LlmSemanticVersions;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.persistence.model.DbReadResult;
import com.aijob.analyzer.profile.CurrentProfileResolver;
import com.aijob.analyzer.retrieval.RetrievalSemanticSnapshot;
import com.aijob.analyzer.support.FixtureFactory;
import com.aijob.analyzer.support.InMemoryTraceRecorder;
import com.aijob.analyzer.trace.TraceRecorder;
import com.aijob.analyzer.workflow.domain.AnalysisExecution;
import com.aijob.analyzer.workflow.domain.WorkflowStatus;
import com.aijob.analyzer.workflow.AnalysisOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TraceAcceptanceTest {

    private AnalysisApplicationService service;
    private AnalysisResultRepository repository;
    private ArtifactCache<AnalysisArtifact> cache;
    private CurrentProfileResolver profileResolver;
    private AnalysisOrchestrator orchestrator;
    private InMemoryTraceRecorder recorder;
    private LlmSemanticVersions versions;
    private RetrievalSemanticSnapshot retrievalSnapshot;

    @BeforeEach
    void setUp() {
        repository = mock(AnalysisResultRepository.class);
        cache = mock(ArtifactCache.class);
        profileResolver = mock(CurrentProfileResolver.class);
        orchestrator = mock(AnalysisOrchestrator.class);
        recorder = new InMemoryTraceRecorder();

        versions = new LlmSemanticVersions();
        var node = new LlmSemanticVersions.NodeVersions();
        node.setPrompt("prompt");
        node.setModel("model");
        node.setSchema("schema");
        versions.setResumeParser(node);
        versions.setRequirementParser(node);
        versions.setFinalAnalysis(node);

        retrievalSnapshot = new com.aijob.analyzer.retrieval.RetrievalSemanticSnapshot(
                "v1", "lucene", "1.0", "openai", 20, 20, 60, 5
        );

        AnalysisIdentityFactory identityFactory = new AnalysisIdentityFactory();
        RequirementIdentityFactory requirementIdentityFactory = new RequirementIdentityFactory();

        service = new AnalysisApplicationService(
                identityFactory,
                requirementIdentityFactory,
                repository,
                cache,
                new JvmSingleFlightCoordinator<>(),
                orchestrator,
                profileResolver,
                versions,
                retrievalSnapshot,
                recorder
        );
    }

    @Test
    void ac_tr_05_redis_miss_db_found_should_record_canonical_reuse() {
        var user = "user-1";
        var job = FixtureFactory.jobContext();
        var profile = FixtureFactory.candidateProfile(1L, user);
        var artifact = new AnalysisArtifact(1L, FixtureFactory.analysisIdentity(), FixtureFactory.analysisDependencySnapshot(), FixtureFactory.analysisResult(), java.time.Instant.now());

        when(profileResolver.resolveCurrent(user)).thenReturn(Optional.of(profile));
        when(cache.get(any())).thenReturn(new CacheReadResult<>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.MISS, null));
        when(repository.findCanonical(any())).thenReturn(new DbReadResult<>(DbReadOutcome.FOUND, artifact, null));

        AnalysisExecution execution = service.analyze(user, job);

        assertEquals(WorkflowStatus.SUCCESS, execution.status());

        // Verify Trace
        var events = recorder.getEvents();
        assertTrue(events.stream().anyMatch(e -> e.type() == TraceEventType.CANONICAL_REUSE && "REDIS".equals(e.attributes().get("source"))));
        assertTrue(events.stream().anyMatch(e -> e.type() == TraceEventType.CANONICAL_REUSE && "DATABASE".equals(e.attributes().get("source"))));

        // Ensure no workflow was executed
        verify(orchestrator, never()).execute(any());
    }

    @Test
    void ac_tr_11_trace_sink_failure_should_not_affect_business() {
        var user = "user-1";
        var job = FixtureFactory.jobContext();
        var profile = FixtureFactory.candidateProfile(1L, user);
        var artifact = new AnalysisArtifact(1L, FixtureFactory.analysisIdentity(), FixtureFactory.analysisDependencySnapshot(), FixtureFactory.analysisResult(), java.time.Instant.now());

        when(profileResolver.resolveCurrent(user)).thenReturn(Optional.of(profile));
        when(cache.get(any())).thenReturn(new CacheReadResult<>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.HIT, artifact));

        // Let the recorder throw exception
        TraceRecorder failingRecorder = mock(TraceRecorder.class);
        doThrow(new RuntimeException("sink failed")).when(failingRecorder).event(any(), any(), any());

        // Rebuild service with failing recorder
        service = new AnalysisApplicationService(
                new AnalysisIdentityFactory(),
                new RequirementIdentityFactory(),
                repository,
                cache,
                new JvmSingleFlightCoordinator<>(),
                orchestrator,
                profileResolver,
                versions,
                retrievalSnapshot,
                failingRecorder
        );

        assertDoesNotThrow(() -> {
            AnalysisExecution execution = service.analyze(user, job);
            assertEquals(WorkflowStatus.SUCCESS, execution.status());
        });
    }
}
