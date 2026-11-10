package com.aijob.analyzer.llm;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.llm.error.ProviderError;
import com.aijob.analyzer.llm.error.ProviderTransportException;
import com.aijob.analyzer.llm.port.ChatModelClient;
import com.aijob.analyzer.llm.port.ChatModelRequest;
import com.aijob.analyzer.llm.port.ChatModelResponse;
import com.aijob.analyzer.llm.recovery.LlmRecoveryPolicy;
import com.aijob.analyzer.llm.validation.JsonContractParser;
import com.aijob.analyzer.llm.validation.SafeJsonRepair;
import com.aijob.analyzer.profile.ProfileBuildService;
import com.aijob.analyzer.profile.ProfileFactIdAssigner;
import com.aijob.analyzer.profile.domain.ProfileArtifactRef;
import com.aijob.analyzer.profile.port.CandidateProfileRepository;
import com.aijob.analyzer.profile.validation.CandidateProfileDraftValidator;
import com.aijob.analyzer.retrieval.ProfileProjectionService;
import com.aijob.analyzer.retrieval.domain.ProjectionBuildResult;
import com.aijob.analyzer.support.retry.DefaultRetryExecutor;
import com.aijob.analyzer.support.retry.JitterSource;
import com.aijob.analyzer.support.retry.RetryExecutor;
import com.aijob.analyzer.support.retry.Sleeper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LlmFailureInjectionTest {

    private CandidateProfileRepository repository;
    private ResumeParserService parserService;
    private ProfileProjectionService projectionService;
    private LlmRecoveryPolicy recoveryPolicy;
    private ProfileBuildService buildService;
    private ChatModelClient chatModelClient;
    private JsonContractParser jsonParser;
    private SafeJsonRepair jsonRepairer;
    private CandidateProfileDraftValidator draftValidator;
    private ProfileFactIdAssigner factIdAssigner;

    @BeforeEach
    void setUp() {
        repository = mock(CandidateProfileRepository.class);
        chatModelClient = mock(ChatModelClient.class);
        recoveryPolicy = new LlmRecoveryPolicy(new com.aijob.analyzer.llm.recovery.LlmErrorClassifier());
        projectionService = mock(ProfileProjectionService.class);

        jsonParser = mock(JsonContractParser.class);
        jsonRepairer = mock(SafeJsonRepair.class);
        draftValidator = mock(CandidateProfileDraftValidator.class);
        factIdAssigner = mock(ProfileFactIdAssigner.class);

        Sleeper sleeper = duration -> {};
        JitterSource jitter = () -> 0.5;
        RetryExecutor retryExecutor = new DefaultRetryExecutor(sleeper, jitter);

        parserService = spy(new ResumeParserService(
                chatModelClient,
                jsonParser,
                jsonRepairer,
                draftValidator,
                factIdAssigner
        ));
        ReflectionTestUtils.setField(parserService, "resumeParserPromptResource", new ByteArrayResource("prompt".getBytes(StandardCharsets.UTF_8)));

        buildService = new ProfileBuildService(
                repository, parserService, projectionService, retryExecutor, recoveryPolicy);
    }

    @Test
    @DisplayName("AC-LLM-06: 429 error should be retried and eventually succeed")
    void test429RetrySuccess() {
        ProfileArtifactIdentity identity = new ProfileArtifactIdentity("u1", 1L);
        when(repository.createBuilding(anyString(), anyString(), anyString()))
                .thenReturn(new ProfileArtifactRef(1L, identity));

        when(chatModelClient.generate(any(ChatModelRequest.class)))
                .thenThrow(new ProviderError(429, "RATE_LIMIT", "Too many requests"))
                .thenReturn(new ChatModelResponse("{}", "req1", "m1", "v1", 10, 20, "stop"));

        when(jsonRepairer.repair(anyString())).thenReturn(Optional.empty());
        when(jsonParser.parse(anyString(), eq(com.aijob.analyzer.profile.domain.CandidateProfileDraft.class)))
                .thenReturn(new com.aijob.analyzer.profile.domain.CandidateProfileDraft(List.of(), List.of(), List.of()));

        when(projectionService.buildProjections(any())).thenReturn(ProjectionBuildResult.SUCCESS);

        buildService.buildAndPublish("u1", "s1", "t1", "content", null, null, null);

        verify(chatModelClient, times(2)).generate(any(ChatModelRequest.class));
        verify(repository).markReadyAndPublishCurrent(eq(identity));
    }

    @Test
    @DisplayName("AC-LLM-08: Timeout should be retried and eventually fail if persistent")
    void testTimeoutPersistentFailure() {
        ProfileArtifactIdentity identity = new ProfileArtifactIdentity("u1", 1L);
        when(repository.createBuilding(anyString(), anyString(), anyString()))
                .thenReturn(new ProfileArtifactRef(1L, identity));

        when(chatModelClient.generate(any(ChatModelRequest.class)))
                .thenThrow(new ProviderTransportException("timeout", null));

        buildService.buildAndPublish("u1", "s1", "t1", "content", null, null, null);

        verify(chatModelClient, times(3)).generate(any(ChatModelRequest.class));
        verify(repository, never()).markReadyAndPublishCurrent(any(ProfileArtifactIdentity.class));
        verify(repository).markFailed(eq(identity), anyString());
    }

    @Test
    @DisplayName("AC-LLM-09: 401 error should fail immediately without retry")
    void test401ImmediateFailure() {
        ProfileArtifactIdentity identity = new ProfileArtifactIdentity("u1", 1L);
        when(repository.createBuilding(anyString(), anyString(), anyString()))
                .thenReturn(new ProfileArtifactRef(1L, identity));

        when(chatModelClient.generate(any(ChatModelRequest.class)))
                .thenThrow(new ProviderError(401, "UNAUTHORIZED", "Invalid API Key"));

        buildService.buildAndPublish("u1", "s1", "t1", "content", null, null, null);

        verify(chatModelClient, times(1)).generate(any(ChatModelRequest.class));
        verify(repository).markFailed(eq(identity), anyString());
    }
}
