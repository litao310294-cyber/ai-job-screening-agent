package com.aijob.analyzer.requirement;

import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.analysis.identity.JobFingerprint;
import com.aijob.analyzer.analysis.identity.ParserDependencyFingerprint;
import com.aijob.analyzer.analysis.identity.RequirementIdentity;
import com.aijob.analyzer.llm.LlmExecutionContext;
import com.aijob.analyzer.llm.RequirementParserService;
import com.aijob.analyzer.llm.recovery.LlmRecoveryPolicy;
import com.aijob.analyzer.llm.recovery.LlmServiceRole;
import com.aijob.analyzer.llm.recovery.RecoveryAction;
import com.aijob.analyzer.requirement.domain.RequirementSet;
import com.aijob.analyzer.requirement.domain.ValidatedRequirementPayload;
import com.aijob.analyzer.requirement.port.RequirementSetRepository;
import com.aijob.analyzer.support.retry.DefaultRetryExecutor;
import com.aijob.analyzer.support.retry.JitterSource;
import com.aijob.analyzer.support.retry.RetryExecutor;
import com.aijob.analyzer.support.retry.Sleeper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RequirementResolutionServiceTest {

    private RequirementSetRepository repository;
    private RequirementParserService parserService;
    private LlmRecoveryPolicy recoveryPolicy;
    private RequirementResolutionService resolutionService;

    @BeforeEach
    void setUp() {
        repository = mock(RequirementSetRepository.class);
        parserService = mock(RequirementParserService.class);
        recoveryPolicy = mock(LlmRecoveryPolicy.class);

        Sleeper sleeper = duration -> {};
        JitterSource jitter = () -> 0.5;
        RetryExecutor retryExecutor = new DefaultRetryExecutor(sleeper, jitter);

        resolutionService = new RequirementResolutionService(
                repository, parserService, retryExecutor, recoveryPolicy);
    }

    @Test
    @DisplayName("Should resolve and save requirement set")
    void testSuccessfulResolve() {
        JobContext job = new JobContext("p1", "j1", "t1", "c1", 3, "6m", "20k", "desc", new JobFingerprint("f1"));
        RequirementIdentity identity = new RequirementIdentity(new JobFingerprint("f1"), new ParserDependencyFingerprint("p1"));

        ValidatedRequirementPayload payload = new ValidatedRequirementPayload(identity, List.of(), List.of(), List.of());
        when(parserService.parseAndValidate(any(), any(), any(), any(), any())).thenReturn(payload);

        RequirementSet expected = new RequirementSet(1L, identity, List.of(), List.of(), List.of());
        when(repository.insertCanonical(any())).thenReturn(expected);

        RequirementSet result = resolutionService.resolve(job, identity, null, null, null);

        assertEquals(expected, result);
        verify(repository).insertCanonical(payload);
    }

    @Test
    @DisplayName("AC-LLM-12: Should fail if requirement parser persistently fails")
    void testPersistentFailure() {
        JobContext job = new JobContext("p1", "j1", "t1", "c1", 3, "6m", "20k", "desc", new JobFingerprint("f1"));
        RequirementIdentity identity = new RequirementIdentity(new JobFingerprint("f1"), new ParserDependencyFingerprint("p1"));

        when(parserService.parseAndValidate(any(), any(), any(), any(), any())).thenThrow(new RuntimeException("LLM error"));
        when(recoveryPolicy.decide(eq(LlmServiceRole.REQUIREMENT_PARSER), any(), anyInt(), anyInt()))
                .thenReturn(RecoveryAction.RETRY, RecoveryAction.RETRY, RecoveryAction.FAIL);

        try {
            resolutionService.resolve(job, identity, null, null, null);
        } catch (Exception ignored) {}

        verify(parserService, times(3)).parseAndValidate(any(), any(), any(), any(), any());
        verify(repository, never()).insertCanonical(any());
    }
}
