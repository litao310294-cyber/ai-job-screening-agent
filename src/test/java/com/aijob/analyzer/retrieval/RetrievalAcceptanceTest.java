package com.aijob.analyzer.retrieval;

import com.aijob.analyzer.analysis.identity.JobFingerprint;
import com.aijob.analyzer.analysis.identity.ParserDependencyFingerprint;
import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.analysis.identity.RequirementIdentity;
import com.aijob.analyzer.profile.ProfileBuildService;
import com.aijob.analyzer.llm.ResumeParserService;
import com.aijob.analyzer.llm.recovery.LlmRecoveryPolicy;
import com.aijob.analyzer.support.retry.RetryExecutor;
import com.aijob.analyzer.profile.domain.*;
import com.aijob.analyzer.profile.port.CandidateProfileRepository;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.persistence.model.DbReadResult;
import com.aijob.analyzer.requirement.domain.AtomicRequirement;
import com.aijob.analyzer.requirement.domain.RequirementPriority;
import com.aijob.analyzer.requirement.domain.RequirementSet;
import com.aijob.analyzer.retrieval.domain.*;
import com.aijob.analyzer.retrieval.fusion.RrfConfig;
import com.aijob.analyzer.retrieval.fusion.RrfFusion;
import com.aijob.analyzer.retrieval.vector.VectorRetriever;
import com.aijob.analyzer.retrieval.vector.ExactCosineRetriever;
import com.aijob.analyzer.retrieval.vector.InMemoryVectorProjectionStore;
import com.aijob.analyzer.retrieval.vector.VectorRecord;
import com.aijob.analyzer.support.DeterministicEmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetrievalAcceptanceTest {

    private ProfileRetrievalService retrievalService;
    private KeywordRetriever keywordRetriever;
    private VectorRetriever vectorRetriever;
    private CandidateProfileRepository profileRepository;
    private ProfileArtifactIdentity profileIdentity;
    private RetrievalSemanticSnapshot snapshot;

    @BeforeEach
    void setUp() {
        keywordRetriever = mock(KeywordRetriever.class);
        vectorRetriever = mock(VectorRetriever.class);
        profileRepository = mock(CandidateProfileRepository.class);

        snapshot = new RetrievalSemanticSnapshot(
                "v1", "lucene", "v1", "model1", 20, 20, 60, 5
        );

        retrievalService = new ProfileRetrievalService(
                keywordRetriever,
                vectorRetriever,
                new RrfFusion(new RrfConfig(snapshot.rrfK())),
                new EvidenceSelector(),
                new RetrievalQueryBuilder(),
                profileRepository,
                new DefaultProfileChunker(),
                snapshot
        );

        profileIdentity = new ProfileArtifactIdentity("U1", 1);
    }

    private RequirementSet createReqSet(String reqId, String text) {
        return new RequirementSet(
                100L,
                new RequirementIdentity(new JobFingerprint("job1"), new ParserDependencyFingerprint("dep1")),
                List.of(),
                List.of(new AtomicRequirement(reqId, "SKILL", text, RequirementPriority.REQUIRED, text)),
                List.of()
        );
    }

    private void mockProfile(ProfileArtifactIdentity identity, List<ProfileFact> facts) {
        CandidateProfile profile = new CandidateProfile(100L, identity, "h1", "d1", ProfileStatus.READY, List.of(), facts, List.of());
        when(profileRepository.find(identity)).thenReturn(new DbReadResult<>(DbReadOutcome.FOUND, profile, null));
    }

    @Test
    void ac_rt_01_exact_technical_term_ranks_matching_fact() {
        mockProfile(profileIdentity, List.of(new ProfileFact("F1", 100L, "SKILL", "Redis Lua", new SourceReference("S1", "T", "S", "T"))));
        RequirementSet reqs = createReqSet("R1", "Redis Lua");

        when(keywordRetriever.search(eq(profileIdentity), argThat(q -> q != null && q.text().equals("Redis Lua")), anyInt()))
                .thenReturn(List.of(new RankedHit("F1-C001", "F1", 10.0, 1)));
        when(vectorRetriever.search(any(), any(), anyInt())).thenReturn(List.of());

        RetrievalResult result = retrievalService.retrieve(profileIdentity, reqs, null, null);

        assertThat(result.evidenceByRequirement().get("R1")).isNotEmpty();
        assertThat(result.evidenceByRequirement().get("R1").get(0).factId()).isEqualTo("F1");
    }

    @Test
    void ac_rt_02_vector_semantic_paraphrase_produces_candidate() {
        InMemoryVectorProjectionStore store = new InMemoryVectorProjectionStore();
        DeterministicEmbeddingClient embeddingClient = new DeterministicEmbeddingClient("model1", "v1");
        ExactCosineRetriever realRetriever = new ExactCosineRetriever(store, embeddingClient, snapshot);

        retrievalService = new ProfileRetrievalService(
                keywordRetriever, realRetriever, new RrfFusion(new RrfConfig(60)),
                new EvidenceSelector(), new RetrievalQueryBuilder(), profileRepository,
                new DefaultProfileChunker(), snapshot
        );

        SourceReference source = new SourceReference("S1", "T", "S", "T");
        ProfileFact fact = new ProfileFact("F1", 100L, "SKILL", "分布式锁实现", source);
        mockProfile(profileIdentity, List.of(fact));

        store.store(profileIdentity, List.of(new VectorRecord("F1-C001", "F1", profileIdentity, "model1", "v1", 2, new float[]{1.0f, 0.0f})));

        embeddingClient.register("Distributed Lock", new float[]{0.9f, 0.1f});
        RequirementSet reqs = createReqSet("R1", "Distributed Lock");

        when(keywordRetriever.search(any(), any(), anyInt())).thenReturn(List.of());

        RetrievalResult result = retrievalService.retrieve(profileIdentity, reqs, null, null);

        assertThat(result.evidenceByRequirement().get("R1")).isNotEmpty();
        assertThat(result.evidenceByRequirement().get("R1").get(0).factId()).isEqualTo("F1");
    }

    @Test
    void ac_rt_03_fusion_uses_ranks_not_raw_scores() {
        RrfFusion fusion = new RrfFusion(new RrfConfig(60));

        RankedHit kwA = new RankedHit("A", "F1", 100.0, 2);
        RankedHit kwB = new RankedHit("B", "F2", 1.0, 1);

        List<FusedHit> fused = fusion.fuse(List.of(kwA, kwB), List.of());

        assertThat(fused.get(0).chunkId()).isEqualTo("B");
        assertThat(fused.get(1).chunkId()).isEqualTo("A");
        assertThat(fused.get(0).rrfScore()).isGreaterThan(fused.get(1).rrfScore());
    }

    @Test
    void ac_rt_04_same_fact_multiple_chunks_one_evidence_slot() {
        EvidenceSelector selector = new EvidenceSelector();
        AtomicRequirement req = new AtomicRequirement("R1", "S", "T", RequirementPriority.REQUIRED, "T");

        List<FusedHit> hits = List.of(
                new FusedHit("C1", "F1", 0.9, 1),
                new FusedHit("C2", "F1", 0.8, 2),
                new FusedHit("C3", "F1", 0.7, 3)
        );

        SourceReference source = new SourceReference("S1", "T", "S", "T");
        Map<String, ProfileChunk> chunks = Map.of(
                "C1", new ProfileChunk("C1", profileIdentity, "F1", "S", "T1", source, Map.of()),
                "C2", new ProfileChunk("C2", profileIdentity, "F1", "S", "T2", source, Map.of()),
                "C3", new ProfileChunk("C3", profileIdentity, "F1", "S", "T3", source, Map.of())
        );

        List<Evidence> selected = selector.select(req, hits, chunks, 5);

        assertThat(selected).hasSize(1);
        assertThat(selected.get(0).factId()).isEqualTo("F1");
    }

    @Test
    void ac_rt_05_final_top_k_distinct_facts() {
        EvidenceSelector selector = new EvidenceSelector();
        AtomicRequirement req = new AtomicRequirement("R1", "S", "T", RequirementPriority.REQUIRED, "T");

        List<FusedHit> hits = List.of(
                new FusedHit("C1", "F1", 0.9, 1),
                new FusedHit("C2", "F2", 0.8, 2),
                new FusedHit("C3", "F3", 0.7, 3),
                new FusedHit("C4", "F4", 0.6, 4),
                new FusedHit("C5", "F5", 0.5, 5),
                new FusedHit("C6", "F6", 0.4, 6)
        );

        SourceReference source = new SourceReference("S1", "T", "S", "T");
        Map<String, ProfileChunk> chunks = hits.stream().collect(Collectors.toMap(
                FusedHit::chunkId,
                h -> new ProfileChunk(h.chunkId(), profileIdentity, h.factId(), "S", "T", source, Map.of())
        ));

        List<Evidence> selected = selector.select(req, hits, chunks, 5);
        assertThat(selected).hasSize(5);
        assertThat(selected.stream().map(Evidence::factId).collect(Collectors.toList()))
                .containsExactly("F1", "F2", "F3", "F4", "F5");
    }

    @Test
    void ac_rt_06_current_version_changes_mid_request_isolation() {
        ProfileArtifactIdentity frozenIdentity = new ProfileArtifactIdentity("U1", 100);
        mockProfile(frozenIdentity, List.of());
        RequirementSet reqs = createReqSet("R1", "Java");

        retrievalService.retrieve(frozenIdentity, reqs, null, null);

        verify(keywordRetriever).search(eq(frozenIdentity), any(), anyInt());
        verify(vectorRetriever).search(eq(frozenIdentity), any(), anyInt());
    }

    @Test
    void ac_rt_07_projection_failure_preserves_old_current() {
        CandidateProfileRepository mockRepo = mock(CandidateProfileRepository.class);
        ProfileProjectionService mockProj = mock(ProfileProjectionService.class);
        ProfileBuildService buildService = new ProfileBuildService(mockRepo, mock(ResumeParserService.class), mockProj, mock(RetryExecutor.class), mock(LlmRecoveryPolicy.class));

        ProfileArtifactIdentity newIdentity = new ProfileArtifactIdentity("U1", 101);
        CandidateProfile newProfile = new CandidateProfile(101L, newIdentity, "h2", "d2", ProfileStatus.BUILDING, List.of(), List.of(), List.of());

        when(mockRepo.createBuilding(anyString(), anyString(), anyString()))
                .thenReturn(new ProfileArtifactRef(101L, newIdentity));
        when(mockRepo.find(newIdentity)).thenReturn(new DbReadResult<>(DbReadOutcome.FOUND, newProfile, null));
        when(mockProj.buildProjections(any())).thenReturn(ProjectionBuildResult.FAILURE);

        buildService.buildAndPublish("U1", "s1", "t1", "content", null, null, null);
        verify(mockRepo, never()).markReadyAndPublishCurrent(any());
    }

    @Test
    void ac_rt_08_nearest_candidate_remains_evidence_not_threshold_rejected() {
        mockProfile(profileIdentity, List.of(new ProfileFact("F1", 100L, "SKILL", "Weak Match", new SourceReference("S1", "T", "S", "T"))));
        RequirementSet reqs = createReqSet("R1", "Target");

        when(keywordRetriever.search(any(), any(), anyInt())).thenReturn(List.of());
        when(vectorRetriever.search(any(), any(), anyInt()))
                .thenReturn(List.of(new RankedHit("F1-C001", "F1", 0.01, 1)));

        RetrievalResult result = retrievalService.retrieve(profileIdentity, reqs, null, null);

        assertThat(result.evidenceByRequirement().get("R1")).hasSize(1);
    }

    @Test
    void ac_rt_09_only_retrieval_facts_become_projections() {
        SourceReference source = new SourceReference("S1", "T", "S", "T");
        ProfileFact f1 = new ProfileFact("F1", 100L, "RETRIEVAL", "Fact 1", source);
        StructuredFact f2 = new StructuredFact("SKILL", "Java", source);
        ContextOnlyFact f3 = new ContextOnlyFact("CATEGORY", "Context", source);
        CandidateProfile profile = new CandidateProfile(100L, profileIdentity, "h1", "d1", ProfileStatus.READY, List.of(f2), List.of(f1), List.of(f3));

        DefaultProfileChunker chunker = new DefaultProfileChunker();
        List<ProfileChunk> chunks = chunker.chunk(profile);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).factId()).isEqualTo("F1");
    }

    @Test
    void ac_rt_10_no_evidence_four_semantics() {
        mockProfile(profileIdentity, List.of());

        // 1. 0 requirements -> SUCCESS empty
        RequirementSet emptySet = new RequirementSet(1L, mock(RequirementIdentity.class), List.of(), List.of(), List.of());
        RetrievalResult result1 = retrievalService.retrieve(profileIdentity, emptySet, null, null);
        assertThat(result1.evidenceByRequirement()).isEmpty();

        // 2. all evidence empty -> NO_EVIDENCE (handled by Stage)
        when(keywordRetriever.search(any(), any(), anyInt())).thenReturn(List.of());
        when(vectorRetriever.search(any(), any(), anyInt())).thenReturn(List.of());
        RetrievalResult result2 = retrievalService.retrieve(profileIdentity, createReqSet("R1", "Java"), null, null);
        assertThat(result2.evidenceByRequirement().get("R1")).isEmpty();

        // 3. partial empty -> SUCCESS (handled by Stage)
        AtomicRequirement r1 = new AtomicRequirement("R1", "S", "Java", RequirementPriority.REQUIRED, "Java");
        AtomicRequirement r2 = new AtomicRequirement("R2", "S", "Spring", RequirementPriority.REQUIRED, "Spring");
        RequirementSet partialSet = new RequirementSet(1L, mock(RequirementIdentity.class), List.of(), List.of(r1, r2), List.of());

        mockProfile(profileIdentity, List.of(new ProfileFact("F1", 100L, "S", "Java", new SourceReference("S1", "T", "S", "T"))));

        when(keywordRetriever.search(any(), argThat(q -> q != null && q.requirementId().equals("R1")), anyInt()))
                .thenReturn(List.of(new RankedHit("F1-C001", "F1", 1.0, 1)));
        when(keywordRetriever.search(any(), argThat(q -> q != null && q.requirementId().equals("R2")), anyInt()))
                .thenReturn(List.of());

        RetrievalResult result3 = retrievalService.retrieve(profileIdentity, partialSet, null, null);
        assertThat(result3.evidenceByRequirement().get("R1")).isNotEmpty();
        assertThat(result3.evidenceByRequirement().get("R2")).isEmpty();
    }

    @Test
    void ac_rt_11_lock_parameters() {
        assertThat(snapshot.keywordCandidateLimit()).isEqualTo(20);
        assertThat(snapshot.vectorCandidateLimit()).isEqualTo(20);
        assertThat(snapshot.rrfK()).isEqualTo(60);
        assertThat(snapshot.finalEvidenceTopK()).isEqualTo(5);
    }

    @Test
    void ac_rt_12_concurrent_publish_monotonicity() {
        CandidateProfileRepository mockRepo = mock(CandidateProfileRepository.class);
        ProfileProjectionService mockProj = mock(ProfileProjectionService.class);
        ProfileBuildService buildService = new ProfileBuildService(mockRepo, mock(ResumeParserService.class), mockProj, mock(RetryExecutor.class), mock(LlmRecoveryPolicy.class));

        ProfileArtifactIdentity p100 = new ProfileArtifactIdentity("U1", 100);
        ProfileArtifactIdentity p101 = new ProfileArtifactIdentity("U1", 101);

        when(mockRepo.createBuilding(eq("U1"), anyString(), anyString()))
                .thenReturn(new ProfileArtifactRef(100L, p100))
                .thenReturn(new ProfileArtifactRef(101L, p101));
        when(mockRepo.find(p100)).thenReturn(new DbReadResult<>(DbReadOutcome.FOUND, new CandidateProfile(100L, p100, "h", "d", ProfileStatus.BUILDING, List.of(), List.of(), List.of()), null));
        when(mockRepo.find(p101)).thenReturn(new DbReadResult<>(DbReadOutcome.FOUND, new CandidateProfile(101L, p101, "h", "d", ProfileStatus.BUILDING, List.of(), List.of(), List.of()), null));
        when(mockProj.buildProjections(any())).thenReturn(ProjectionBuildResult.SUCCESS);

        when(mockRepo.markReadyAndPublishCurrent(p101)).thenReturn(ProfilePublishOutcome.PUBLISHED_CURRENT);
        assertThat(buildService.buildAndPublish("U1", "s1", "t1", "c1", null, null, null)).isEqualTo(p100);

        when(mockRepo.markReadyAndPublishCurrent(p100)).thenReturn(ProfilePublishOutcome.READY_NOT_CURRENT);
        assertThat(buildService.buildAndPublish("U1", "s1", "t1", "c2", null, null, null)).isEqualTo(p101);
    }
}
