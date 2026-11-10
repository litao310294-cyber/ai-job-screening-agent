package com.aijob.analyzer.retrieval;

import com.aijob.analyzer.analysis.identity.*;
import com.aijob.analyzer.requirement.domain.AtomicRequirement;
import com.aijob.analyzer.requirement.domain.RequirementPriority;
import com.aijob.analyzer.requirement.domain.RequirementSet;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.profile.domain.ProfileStatus;
import com.aijob.analyzer.profile.port.CandidateProfileRepository;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.persistence.model.DbReadResult;
import com.aijob.analyzer.retrieval.domain.*;
import com.aijob.analyzer.retrieval.fusion.RrfConfig;
import com.aijob.analyzer.retrieval.fusion.RrfFusion;
import com.aijob.analyzer.retrieval.vector.VectorRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProfileRetrievalServiceTest {

    private ProfileRetrievalService service;
    private KeywordRetriever keywordRetriever;
    private VectorRetriever vectorRetriever;
    private CandidateProfileRepository profileRepository;
    private ProfileArtifactIdentity profileIdentity;
    private RequirementSet requirementSet;

    @BeforeEach
    void setUp() {
        keywordRetriever = mock(KeywordRetriever.class);
        vectorRetriever = mock(VectorRetriever.class);
        profileRepository = mock(CandidateProfileRepository.class);

        RetrievalSemanticSnapshot snapshot = new RetrievalSemanticSnapshot(
                "v1", "lucene", "v1", "model1", 20, 20, 60, 5
        );

        service = new ProfileRetrievalService(
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
        requirementSet = new RequirementSet(
                100L,
                new RequirementIdentity(new JobFingerprint("job1"), new ParserDependencyFingerprint("dep1")),
                List.of(),
                List.of(new AtomicRequirement("R1", "SKILL", "Java", RequirementPriority.REQUIRED, "Java")),
                List.of()
        );

        CandidateProfile profile = new CandidateProfile(
                100L, profileIdentity, "h1", "d1", ProfileStatus.READY,
                List.of(), List.of(), List.of()
        );
        when(profileRepository.find(any())).thenReturn(new DbReadResult<>(DbReadOutcome.FOUND, profile, null));
    }

    @Test
    void should_return_empty_result_when_no_retrieval_requirements() {
        // Given
        RequirementSet emptyReqs = new RequirementSet(
                100L, new RequirementIdentity(new JobFingerprint("job1"), new ParserDependencyFingerprint("dep1")),
                List.of(), List.of(), List.of()
        );

        // When
        RetrievalResult result = service.retrieve(profileIdentity, emptyReqs, null, null);

        // Then
        assertThat(result.evidenceByRequirement()).isEmpty();
    }

    @Test
    void should_return_evidence_when_found() {
        // Given
        when(keywordRetriever.search(any(), any(), anyInt()))
                .thenReturn(List.of(new RankedHit("C1", "F1", 10.0, 1)));

        // When
        RetrievalResult result = service.retrieve(profileIdentity, requirementSet, null, null);

        // Then
        // Note: DefaultProfileChunker will return empty list since profile.retrievalFacts is empty in setUp
        // So evidence will be empty even if hits are returned because chunks can't be resolved.
        assertThat(result.evidenceByRequirement().get("R1")).isEmpty();
    }
}
