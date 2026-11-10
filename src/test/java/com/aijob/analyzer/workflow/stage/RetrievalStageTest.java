package com.aijob.analyzer.workflow.stage;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.persistence.model.DbReadResult;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.profile.domain.ProfileStatus;
import com.aijob.analyzer.profile.domain.SourceReference;
import com.aijob.analyzer.profile.port.CandidateProfileRepository;
import com.aijob.analyzer.requirement.domain.AtomicRequirement;
import com.aijob.analyzer.requirement.domain.RequirementPriority;
import com.aijob.analyzer.requirement.domain.RequirementSet;
import com.aijob.analyzer.retrieval.ProfileRetrievalService;
import com.aijob.analyzer.retrieval.domain.Evidence;
import com.aijob.analyzer.retrieval.domain.RetrievalResult;
import com.aijob.analyzer.llm.LlmSemanticVersions;
import com.aijob.analyzer.requirement.RequirementResolutionService;
import com.aijob.analyzer.workflow.AnalysisContext;
import com.aijob.analyzer.workflow.StageResult;
import com.aijob.analyzer.workflow.domain.StageSignal;
import com.aijob.analyzer.support.FixtureFactory;
import com.aijob.analyzer.support.time.ExecutionDeadline;
import com.aijob.analyzer.support.time.TimeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalStageTest {

    private RetrievalStage stage;
    private ProfileRetrievalService retrievalService;
    private RequirementResolutionService requirementResolutionService;
    private LlmSemanticVersions versions;
    private CandidateProfileRepository profileRepository;
    private AnalysisContext context;
    private ProfileArtifactIdentity profileIdentity;

    @BeforeEach
    void setUp() {
        retrievalService = mock(ProfileRetrievalService.class);
        requirementResolutionService = mock(RequirementResolutionService.class);
        versions = mock(LlmSemanticVersions.class);
        profileRepository = mock(CandidateProfileRepository.class);
        stage = new RetrievalStage(retrievalService, requirementResolutionService, versions);

        profileIdentity = new ProfileArtifactIdentity("U1", 1);
        var profile = new CandidateProfile(1L, profileIdentity, "h1", "d1", ProfileStatus.READY, List.of(), List.of(), List.of());
        when(profileRepository.find(any())).thenReturn(new DbReadResult<>(DbReadOutcome.FOUND, profile, null));

        context = new AnalysisContext(
                "t1", "task1", FixtureFactory.jobContext(), profile, profileIdentity,
                FixtureFactory.analysisIdentity(), ExecutionDeadline.fromNow(Duration.ofSeconds(60), TimeSource.SYSTEM),
                FixtureFactory.analysisDependencySnapshot()
        );
    }

    @Test
    void should_return_success_when_no_requirements() {
        // Given
        context.setRequirementSet(new RequirementSet(1L, FixtureFactory.requirementIdentity(), List.of(), List.of(), List.of()));

        // When
        StageResult result = stage.execute(context);

        // Then
        assertThat(result.signal()).isEqualTo(StageSignal.SUCCESS);
        assertThat(context.retrievalResult()).isNotNull();
    }

    @Test
    void should_return_no_evidence_when_all_empty() {
        // Given
        var req = new AtomicRequirement("R1", "SKILL", "Java", RequirementPriority.REQUIRED, "Java");
        context.setRequirementSet(new RequirementSet(1L, FixtureFactory.requirementIdentity(), List.of(), List.of(req), List.of()));

        when(retrievalService.retrieve(any(), any(), any(), any())).thenReturn(new RetrievalResult(Map.of("R1", List.of())));

        // When
        StageResult result = stage.execute(context);

        // Then
        assertThat(result.signal()).isEqualTo(StageSignal.NO_EVIDENCE);
    }

    @Test
    void should_return_success_when_some_have_evidence() {
        // Given
        var req1 = new AtomicRequirement("R1", "SKILL", "Java", RequirementPriority.REQUIRED, "Java");
        var req2 = new AtomicRequirement("R2", "SKILL", "Spring", RequirementPriority.REQUIRED, "Spring");
        context.setRequirementSet(new RequirementSet(1L, FixtureFactory.requirementIdentity(), List.of(), List.of(req1, req2), List.of()));

        var source = new SourceReference("S1", "T", "S", "Text");
        var evidence = new Evidence("F1", "R1", "F1", "Java expert", source);
        when(retrievalService.retrieve(any(), any(), any(), any())).thenReturn(new RetrievalResult(Map.of(
                "R1", List.of(evidence),
                "R2", List.of()
        )));

        // When
        StageResult result = stage.execute(context);

        // Then
        assertThat(result.signal()).isEqualTo(StageSignal.SUCCESS);
    }
}
