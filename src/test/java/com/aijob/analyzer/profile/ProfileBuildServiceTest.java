package com.aijob.analyzer.profile;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.llm.LlmExecutionContext;
import com.aijob.analyzer.llm.LlmSemanticSnapshot;
import com.aijob.analyzer.llm.ResumeParserService;
import com.aijob.analyzer.llm.recovery.LlmRecoveryPolicy;
import com.aijob.analyzer.llm.recovery.LlmServiceRole;
import com.aijob.analyzer.llm.recovery.RecoveryAction;
import com.aijob.analyzer.profile.domain.*;
import com.aijob.analyzer.profile.port.CandidateProfileRepository;
import com.aijob.analyzer.retrieval.ProfileProjectionService;
import com.aijob.analyzer.retrieval.domain.ProjectionBuildResult;
import com.aijob.analyzer.support.retry.DefaultRetryExecutor;
import com.aijob.analyzer.support.retry.JitterSource;
import com.aijob.analyzer.support.retry.RetryExecutor;
import com.aijob.analyzer.support.retry.Sleeper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProfileBuildServiceTest {

    private CandidateProfileRepository repository;
    private ResumeParserService parserService;
    private ProfileProjectionService projectionService;
    private LlmRecoveryPolicy recoveryPolicy;
    private ProfileBuildService buildService;

    @BeforeEach
    void setUp() {
        repository = mock(CandidateProfileRepository.class);
        parserService = mock(ResumeParserService.class);
        projectionService = mock(ProfileProjectionService.class);
        recoveryPolicy = mock(LlmRecoveryPolicy.class);

        Sleeper sleeper = duration -> {}; // No sleep in tests
        JitterSource jitter = () -> 0.5;
        RetryExecutor retryExecutor = new DefaultRetryExecutor(sleeper, jitter);

        buildService = new ProfileBuildService(
                repository, parserService, projectionService, retryExecutor, recoveryPolicy);
    }

    @Test
    @DisplayName("Should publish profile after successful parse and projection")
    void testSuccessfulBuild() {
        String userId = "u1";
        ProfileArtifactIdentity identity = new ProfileArtifactIdentity(userId, 1L);
        when(repository.createBuilding(anyString(), anyString(), anyString()))
                .thenReturn(new ProfileArtifactRef(1L, identity));

        CandidateProfile profile = new CandidateProfile(1L, identity, "f1", "m1", ProfileStatus.READY, List.of(), List.of(), List.of());
        when(parserService.parse(any(), any(), any(), any(), any())).thenReturn(profile);
        when(projectionService.buildProjections(any())).thenReturn(ProjectionBuildResult.SUCCESS);

        ProfileArtifactIdentity result = buildService.buildAndPublish(userId, "s1", "t1", "resume content", null, null, null);

        assertEquals(identity, result);
        verify(repository).saveValidatedProfile(profile);
        verify(repository).markReadyAndPublishCurrent(identity);
    }

    @Test
    @DisplayName("AC-LLM-11: Should mark failed and not publish if parser persistently fails")
    void testParserFailure() {
        String userId = "u1";
        ProfileArtifactIdentity identity = new ProfileArtifactIdentity(userId, 1L);
        when(repository.createBuilding(anyString(), anyString(), anyString()))
                .thenReturn(new ProfileArtifactRef(1L, identity));

        RuntimeException error = new RuntimeException("LLM down");
        when(parserService.parse(any(), any(), any(), any(), any())).thenThrow(error);

        // Mock recovery policy to fail after 3 attempts
        when(recoveryPolicy.decide(eq(LlmServiceRole.RESUME_PARSER), any(), anyInt(), anyInt()))
                .thenReturn(RecoveryAction.RETRY, RecoveryAction.RETRY, RecoveryAction.FAIL);

        buildService.buildAndPublish(userId, "s1", "t1", "resume content", null, null, null);

        verify(parserService, times(3)).parse(any(), any(), any(), any(), any());
        verify(repository, never()).markReadyAndPublishCurrent(any());
        verify(repository).markFailed(eq(identity), anyString());
    }
}
