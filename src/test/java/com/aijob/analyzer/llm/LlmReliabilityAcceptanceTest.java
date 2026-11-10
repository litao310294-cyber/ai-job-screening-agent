package com.aijob.analyzer.llm;

import com.aijob.analyzer.analysis.domain.*;
import com.aijob.analyzer.analysis.identity.JobFingerprint;
import com.aijob.analyzer.analysis.identity.ParserDependencyFingerprint;
import com.aijob.analyzer.analysis.identity.RequirementIdentity;
import com.aijob.analyzer.llm.error.ProviderError;
import com.aijob.analyzer.llm.error.ProviderTransportException;
import com.aijob.analyzer.llm.port.ChatModelResponse;
import com.aijob.analyzer.llm.recovery.LlmRecoveryPolicy;
import com.aijob.analyzer.llm.recovery.LlmServiceRole;
import com.aijob.analyzer.llm.recovery.RecoveryAction;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.profile.domain.ProfileStatus;
import com.aijob.analyzer.profile.domain.SourceReference;
import com.aijob.analyzer.requirement.domain.AtomicRequirement;
import com.aijob.analyzer.requirement.domain.RequirementPriority;
import com.aijob.analyzer.requirement.domain.RequirementSet;
import com.aijob.analyzer.retrieval.domain.Evidence;
import com.aijob.analyzer.retrieval.domain.RetrievalResult;
import com.aijob.analyzer.support.ScriptedChatModelClient;
import com.aijob.analyzer.workflow.*;
import com.aijob.analyzer.workflow.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
import com.aijob.analyzer.support.retry.RetryableOperation;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:isolated;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false"})
@ActiveProfiles("test")
public class LlmReliabilityAcceptanceTest {

    @Autowired
    private FinalAnalysisService finalAnalysisService;

    @Autowired
    private ResumeParserService resumeParserService;

    @Autowired
    private RequirementParserService requirementParserService;

    @Autowired
    private com.aijob.analyzer.profile.ProfileBuildService profileBuildService;

    @Autowired
    private com.aijob.analyzer.requirement.RequirementResolutionService requirementResolutionService;

    @Autowired
    private LlmSemanticVersions versions;

    @Autowired
    private LlmRecoveryPolicy recoveryPolicy;

    @Autowired
    private FallbackResultFactory fallbackResultFactory;

    private ScriptedChatModelClient chatModelClient;

    @MockBean
    private com.aijob.analyzer.profile.port.CandidateProfileRepository candidateProfileRepository;
    @MockBean
    private com.aijob.analyzer.retrieval.ProfileProjectionService profileProjectionService;
    @MockBean
    private com.aijob.analyzer.requirement.port.RequirementSetRepository requirementSetRepository;
    @MockBean
    private com.aijob.analyzer.support.retry.RetryExecutor retryExecutor;

    @BeforeEach
    void setUp() {
        chatModelClient = new ScriptedChatModelClient();
        // Replace the ChatModelClient in all services with our scripted one
        org.springframework.test.util.ReflectionTestUtils.setField(finalAnalysisService, "chatModelClient", chatModelClient);
        org.springframework.test.util.ReflectionTestUtils.setField(resumeParserService, "chatModelClient", chatModelClient);
        org.springframework.test.util.ReflectionTestUtils.setField(requirementParserService, "chatModelClient", chatModelClient);

        // Mock default behavior for retry executor to just call the action
        when(retryExecutor.execute(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            RetryableOperation<?> operation = invocation.getArgument(5);
            return operation.execute(1);
        });
    }

    @Test
    @DisplayName("AC-LLM-01: HTTP 200 + invalid JSON -> Parse failure")
    void ac_llm_01_invalid_json_parse_failure() {
        chatModelClient.addScript(new ChatModelResponse("invalid json", "req1", "m1", "v1", 10, 20, "stop"));

        JobContext job = createJobContext();
        RequirementSet reqSet = createRequirementSet();
        RetrievalResult retrievalResult = createRetrievalResult();
        LlmExecutionContext context = new LlmExecutionContext(versions.getFinalAnalysisSnapshot(), 1, 0);

        assertThrows(com.aijob.analyzer.llm.validation.JsonParseException.class, () -> {
            finalAnalysisService.generateStructured(job, new RuleResult(RuleSignal.CONTINUE, List.of()), reqSet, List.of(), retrievalResult, context, null, null);
        });
    }

    @Test
    @DisplayName("AC-LLM-02: PARTIAL -> PARTIALLY_SUPPORTED -> revalidate")
    void ac_llm_02_partial_alias_repair() {
        String jsonWithPartial = """
                {
                  "decision": "CONSIDER",
                  "overallAssessment": "Good",
                  "requirementAssessments": [
                    {
                      "requirementId": "R0001",
                      "status": "PARTIAL",
                      "evidenceRefs": ["F0001"],
                      "reasoning": "Some exp",
                      "gap": ""
                    }
                  ],
                  "risks": [],
                  "strengths": [],
                  "recommendation": "Try",
                  "summary": "OK"
                }
                """;
        chatModelClient.addScript(new ChatModelResponse(jsonWithPartial, "req1", "m1", "v1", 10, 20, "stop"));

        JobContext job = createJobContext();
        RequirementSet reqSet = createRequirementSet();
        RetrievalResult retrievalResult = createRetrievalResult();
        LlmExecutionContext context = new LlmExecutionContext(versions.getFinalAnalysisSnapshot(), 1, 0);

        JobAnalysisResult result = finalAnalysisService.generateStructured(job, new RuleResult(RuleSignal.CONTINUE, List.of()), reqSet, List.of(), retrievalResult, context, null, null);

        assertEquals(RequirementAssessmentStatus.PARTIALLY_SUPPORTED, result.requirementAssessments().get(0).status());
    }

    @Test
    @DisplayName("AC-LLM-06: 429 -> bounded retry")
    void ac_llm_06_429_retryable() {
        Throwable t = new ProviderError(429, "RATE_LIMIT", "Too many requests");
        RecoveryAction action = recoveryPolicy.decide(LlmServiceRole.FINAL_ANALYSIS, t, 1, 0);
        assertEquals(RecoveryAction.RETRY, action);

        // Exhausted
        RecoveryAction actionExhausted = recoveryPolicy.decide(LlmServiceRole.FINAL_ANALYSIS, t, 3, 0);
        assertEquals(RecoveryAction.FALLBACK, actionExhausted);
    }

    @Test
    @DisplayName("AC-LLM-10: Final LLM exhausted -> DEGRADED fallback")
    void ac_llm_10_final_llm_exhausted_fallback() {
        AnalysisContext context = createAnalysisContext();
        context.setRequirementSet(createRequirementSet());

        JobAnalysisResult fallback = fallbackResultFactory.fromRuleAndEvidence(context);

        assertEquals(JobDecision.INSUFFICIENT_EVIDENCE, fallback.decision());
        assertEquals(RequirementAssessmentStatus.INSUFFICIENT_EVIDENCE, fallback.requirementAssessments().get(0).status());
        assertTrue(fallback.requirementAssessments().get(0).evidenceRefs().isEmpty());
    }

    @Test
    @DisplayName("AC-LLM-15: no Evidence -> INSUFFICIENT_EVIDENCE legal")
    void ac_llm_15_no_evidence_legal() {
        String json = """
                {
                  "decision": "INSUFFICIENT_EVIDENCE",
                  "overallAssessment": "Need more info",
                  "requirementAssessments": [
                    {
                      "requirementId": "R0001",
                      "status": "INSUFFICIENT_EVIDENCE",
                      "evidenceRefs": [],
                      "reasoning": "No info provided",
                      "gap": "Missing Java details"
                    }
                  ],
                  "risks": [],
                  "strengths": [],
                  "recommendation": "Ask candidate",
                  "summary": "Unknown"
                }
                """;
        chatModelClient.addScript(new ChatModelResponse(json, "req1", "m1", "v1", 10, 20, "stop"));

        JobContext job = createJobContext();
        RequirementSet reqSet = createRequirementSet();
        RetrievalResult retrievalResult = new RetrievalResult(Map.of("R0001", List.of()));
        LlmExecutionContext context = new LlmExecutionContext(versions.getFinalAnalysisSnapshot(), 1, 0);

        JobAnalysisResult result = finalAnalysisService.generateStructured(job, new RuleResult(RuleSignal.CONTINUE, List.of()), reqSet, List.of(), retrievalResult, context, null, null);

        assertEquals(JobDecision.INSUFFICIENT_EVIDENCE, result.decision());
        assertEquals(RequirementAssessmentStatus.INSUFFICIENT_EVIDENCE, result.requirementAssessments().get(0).status());
        assertTrue(result.requirementAssessments().get(0).evidenceRefs().isEmpty());
    }

    @Test
    @DisplayName("AC-LLM-03: Unknown enum -> fail")
    void ac_llm_03_unknown_enum_fail() {
        String json = """
                {
                  "decision": "UNKNOWN_DECISION",
                  "overallAssessment": "Bad",
                  "requirementAssessments": [],
                  "risks": [],
                  "strengths": [],
                  "recommendation": "None",
                  "summary": "None"
                }
                """;
        chatModelClient.addScript(new ChatModelResponse(json, "req1", "m1", "v1", 10, 20, "stop"));

        JobContext job = createJobContext();
        RequirementSet reqSet = createRequirementSet();
        RetrievalResult retrievalResult = createRetrievalResult();
        LlmExecutionContext context = new LlmExecutionContext(versions.getFinalAnalysisSnapshot(), 1, 0);

        assertThrows(com.aijob.analyzer.llm.validation.SchemaValidationError.class, () -> {
            finalAnalysisService.generateStructured(job, new RuleResult(RuleSignal.CONTINUE, List.of()), reqSet, List.of(), retrievalResult, context, null, null);
        });
    }

    @Test
    @DisplayName("AC-LLM-04: 缺重大语义字段 -> 不凭空 Repair")
    void ac_llm_04_missing_major_field_fail() {
        String jsonMissingDecision = """
                {
                  "overallAssessment": "Good",
                  "requirementAssessments": [],
                  "risks": [],
                  "strengths": [],
                  "recommendation": "Rec",
                  "summary": "Sum"
                }
                """;
        chatModelClient.addScript(new ChatModelResponse(jsonMissingDecision, "req1", "m1", "v1", 10, 20, "stop"));

        JobContext job = createJobContext();
        RequirementSet reqSet = createRequirementSet();
        RetrievalResult retrievalResult = createRetrievalResult();
        LlmExecutionContext context = new LlmExecutionContext(versions.getFinalAnalysisSnapshot(), 1, 0);

        assertThrows(com.aijob.analyzer.llm.validation.SchemaValidationError.class, () -> {
            finalAnalysisService.generateStructured(job, new RuleResult(RuleSignal.CONTINUE, List.of()), reqSet, List.of(), retrievalResult, context, null, null);
        });
    }

    @Test
    @DisplayName("AC-LLM-05: Unknown evidenceRef -> Grounding fail")
    void ac_llm_05_unknown_evidence_ref_fail() {
        String json = """
                {
                  "decision": "RECOMMEND_APPLY",
                  "overallAssessment": "Good",
                  "requirementAssessments": [
                    {
                      "requirementId": "R0001",
                      "status": "SUPPORTED",
                      "evidenceRefs": ["F9999"],
                      "reasoning": "Has exp",
                      "gap": ""
                    }
                  ],
                  "risks": [],
                  "strengths": [],
                  "recommendation": "Rec",
                  "summary": "Sum"
                }
                """;
        chatModelClient.addScript(new ChatModelResponse(json, "req1", "m1", "v1", 10, 20, "stop"));

        JobContext job = createJobContext();
        RequirementSet reqSet = createRequirementSet();
        RetrievalResult retrievalResult = createRetrievalResult(); // Only has F0001
        LlmExecutionContext context = new LlmExecutionContext(versions.getFinalAnalysisSnapshot(), 1, 0);

        assertThrows(com.aijob.analyzer.llm.validation.GroundingError.class, () -> {
            finalAnalysisService.generateStructured(job, new RuleResult(RuleSignal.CONTINUE, List.of()), reqSet, List.of(), retrievalResult, context, null, null);
        });
    }

    @Test
    @DisplayName("AC-LLM-07: 503 -> approved retry")
    void ac_llm_07_503_retryable() {
        Throwable t = new ProviderError(503, "SERVICE_UNAVAILABLE", "Unavailable");
        RecoveryAction action = recoveryPolicy.decide(LlmServiceRole.FINAL_ANALYSIS, t, 1, 0);
        assertEquals(RecoveryAction.RETRY, action);
    }

    @Test
    @DisplayName("AC-LLM-09: 401 -> no blind retry")
    void ac_llm_09_401_no_retry() {
        Throwable t = new ProviderError(401, "UNAUTHORIZED", "Invalid key");
        RecoveryAction action = recoveryPolicy.decide(LlmServiceRole.FINAL_ANALYSIS, t, 1, 0);
        assertEquals(RecoveryAction.FAIL, action);
    }

    @Test
    @DisplayName("AC-LLM-16: supported requires evidenceRef (Constructor + Validator)")
    void ac_llm_16_supported_requires_evidence() {
        // Constructor validation
        assertThrows(IllegalArgumentException.class, () -> {
            new RequirementAssessment("R0001", RequirementAssessmentStatus.SUPPORTED, List.of(), "reason", "");
        });

        // Validator check (redundant but safe)
        com.aijob.analyzer.analysis.validation.JobAnalysisResultValidator validator = new com.aijob.analyzer.analysis.validation.JobAnalysisResultValidator();
        RequirementSet reqSet = createRequirementSet();
        RetrievalResult retrievalResult = createRetrievalResult();

        RequirementAssessment assessment = new RequirementAssessment("R0001", RequirementAssessmentStatus.SUPPORTED, List.of("F0001"), "reason", "");
        JobAnalysisResult result = new JobAnalysisResult(JobDecision.RECOMMEND_APPLY, "Good", List.of(assessment), List.of(), List.of(), "Rec", "Sum");

        // Should pass
        assertDoesNotThrow(() -> validator.validate(result, reqSet, retrievalResult));
    }

    @Test
    @DisplayName("ac_llm_11_resume_parser_persistent_failure")
    void ac_llm_11_resume_parser_persistent_failure() {
        // Setup persistent failure
        chatModelClient.addScript(new ProviderError(503, "UNAVAILABLE", "Server busy"));
        chatModelClient.addScript(new ProviderError(503, "UNAVAILABLE", "Server busy"));
        chatModelClient.addScript(new ProviderError(503, "UNAVAILABLE", "Server busy"));

        // Use real retry executor for this test to see the failure
        org.springframework.test.util.ReflectionTestUtils.setField(profileBuildService, "retryExecutor",
            new com.aijob.analyzer.support.retry.DefaultRetryExecutor(d -> {}, () -> 0.0));

        com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity identity = new com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity("u1", 1L);
        when(candidateProfileRepository.createBuilding(any(), any(), any()))
                .thenReturn(new com.aijob.analyzer.profile.domain.ProfileArtifactRef(1L, identity));

        // buildAndPublish catches the exception and returns identity
        com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity result =
            profileBuildService.buildAndPublish("u1", "s1", "t1", "content", versions.getResumeParserSnapshot(), null, null);

        assertEquals(identity, result);

        // Verify that markFailed was called
        verify(candidateProfileRepository).markFailed(eq(identity), anyString());
        verify(candidateProfileRepository, never()).markReadyAndPublishCurrent(any());
    }

    @Test
    @DisplayName("AC-LLM-08: Timeout -> bounded retry")
    void ac_llm_08_timeout_retryable() {
        Throwable t = new ProviderTransportException("timeout", null);
        RecoveryAction action = recoveryPolicy.decide(LlmServiceRole.FINAL_ANALYSIS, t, 1, 0);
        assertEquals(RecoveryAction.RETRY, action);
    }

    @Test
    @DisplayName("AC-LLM-12: Requirement Parser persistent failure -> request fail")
    void ac_llm_12_requirement_parser_persistent_failure() {
        chatModelClient.addScript(new ProviderError(503, "UNAVAILABLE", "Server busy"));
        chatModelClient.addScript(new ProviderError(503, "UNAVAILABLE", "Server busy"));
        chatModelClient.addScript(new ProviderError(503, "UNAVAILABLE", "Server busy"));

        org.springframework.test.util.ReflectionTestUtils.setField(requirementResolutionService, "retryExecutor",
            new com.aijob.analyzer.support.retry.DefaultRetryExecutor(d -> {}, () -> 0.0));

        JobContext job = createJobContext();
        com.aijob.analyzer.analysis.identity.RequirementIdentity identity = new com.aijob.analyzer.analysis.identity.RequirementIdentity(new JobFingerprint("f"), new com.aijob.analyzer.analysis.identity.ParserDependencyFingerprint("p"));

        assertThrows(com.aijob.analyzer.support.retry.OperationFailureException.class, () -> {
            requirementResolutionService.resolve(job, identity, versions.getRequirementParserSnapshot(), null, null);
        });
    }

    @Test
    @DisplayName("AC-LLM-17: Repair whitelist (BOM/whitespace/fence/PARTIAL)")
    void ac_llm_17_repair_whitelist() {
        String jsonWithFence = "```json\n" + """
                {
                  "decision": "CONSIDER",
                  "overallAssessment": "Good",
                  "requirementAssessments": [
                    {
                      "requirementId": "R0001",
                      "status": "PARTIAL",
                      "evidenceRefs": ["F0001"],
                      "reasoning": "Some exp",
                      "gap": ""
                    }
                  ],
                  "risks": [],
                  "strengths": [],
                  "recommendation": "Try",
                  "summary": "OK"
                }
                """ + "\n```";
        chatModelClient.addScript(new ChatModelResponse(jsonWithFence, "req1", "m1", "v1", 10, 20, "stop"));

        JobContext job = createJobContext();
        RequirementSet reqSet = createRequirementSet();
        RetrievalResult retrievalResult = createRetrievalResult();
        LlmExecutionContext context = new LlmExecutionContext(versions.getFinalAnalysisSnapshot(), 1, 0);

        // Should succeed because of repair
        JobAnalysisResult result = finalAnalysisService.generateStructured(job, new RuleResult(RuleSignal.CONTINUE, List.of()), reqSet, List.of(), retrievalResult, context, null, null);
        assertEquals(RequirementAssessmentStatus.PARTIALLY_SUPPORTED, result.requirementAssessments().get(0).status());
    }

    @Test
    @DisplayName("ac_llm_13_resume_parser_valid_output")
    void ac_llm_13_resume_parser_valid_output() {
        String json = """
                {
                  "structuredFacts": [],
                  "retrievalFacts": [
                    {
                      "factType": "SKILL",
                      "normalizedText": "Java",
                      "source": {
                        "sourceId": "s1",
                        "sourceTitle": "t1",
                        "sourceSection": "sec1",
                        "sourceText": "Java developer"
                      }
                    }
                  ],
                  "contextOnly": []
                }
                """;
        chatModelClient.addScript(new ChatModelResponse(json, "req1", "m1", "v1", 10, 20, "stop"));

        com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity identity = new com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity("u1", 1L);
        when(candidateProfileRepository.createBuilding(any(), any(), any()))
                .thenReturn(new com.aijob.analyzer.profile.domain.ProfileArtifactRef(1L, identity));
        when(candidateProfileRepository.find(identity)).thenReturn(new com.aijob.analyzer.persistence.model.DbReadResult<>(
                com.aijob.analyzer.persistence.model.DbReadOutcome.FOUND,
                new CandidateProfile(1L, identity, "h", "d", ProfileStatus.BUILDING, List.of(), List.of(), List.of()),
                null
        ));
        when(profileProjectionService.buildProjections(any())).thenReturn(com.aijob.analyzer.retrieval.domain.ProjectionBuildResult.SUCCESS);

        profileBuildService.buildAndPublish("u1", "s1", "t1", "Java developer here", versions.getResumeParserSnapshot(), null, null);

        ArgumentCaptor<CandidateProfile> profileCaptor = ArgumentCaptor.forClass(CandidateProfile.class);
        verify(candidateProfileRepository).saveValidatedProfile(profileCaptor.capture());

        CandidateProfile savedProfile = profileCaptor.getValue();
        assertEquals(1, savedProfile.retrievalFacts().size());
        assertEquals("F0001", savedProfile.retrievalFacts().get(0).factId());
        assertEquals("Java", savedProfile.retrievalFacts().get(0).normalizedText());
    }

    @Test
    @DisplayName("ac_llm_14_requirement_parser_valid_output")
    void ac_llm_14_requirement_parser_valid_output() {
        String json = """
                {
                  "structuredConstraints": [],
                  "retrievalRequirements": [
                    {
                      "temporaryRequirementId": "temp1",
                      "requirementType": "SKILL",
                      "normalizedText": "Java",
                      "priority": "REQUIRED",
                      "sourceText": "Must have Java"
                    }
                  ],
                  "ignoredContext": []
                }
                """;
        chatModelClient.addScript(new ChatModelResponse(json, "req1", "gpt-4o", "v1", 100, 200, "stop"));

        // Job description must contain the source text
        JobContext job = new JobContext("p", "j", "T", "C", 1, "Must have Java", "S", "Desc", new JobFingerprint("f"));
        com.aijob.analyzer.analysis.identity.RequirementIdentity identity = new com.aijob.analyzer.analysis.identity.RequirementIdentity(new JobFingerprint("f"), new com.aijob.analyzer.analysis.identity.ParserDependencyFingerprint("p"));

        requirementResolutionService.resolve(job, identity, versions.getRequirementParserSnapshot(), null, null);

        ArgumentCaptor<com.aijob.analyzer.requirement.domain.ValidatedRequirementPayload> payloadCaptor = ArgumentCaptor.forClass(com.aijob.analyzer.requirement.domain.ValidatedRequirementPayload.class);
        verify(requirementSetRepository).insertCanonical(payloadCaptor.capture());

        com.aijob.analyzer.requirement.domain.ValidatedRequirementPayload savedPayload = payloadCaptor.getValue();
        assertEquals(1, savedPayload.retrievalRequirements().size());
        assertEquals("Java", savedPayload.retrievalRequirements().get(0).normalizedText());
    }

    private JobContext createJobContext() {
        return new JobContext("p", "j", "T", "C", 1, "D", "S", "Desc", new JobFingerprint("f"));
    }

    private RequirementSet createRequirementSet() {
        RequirementIdentity identity = new RequirementIdentity(new JobFingerprint("f"), new ParserDependencyFingerprint("p"));
        AtomicRequirement req1 = new AtomicRequirement("R0001", "SKILL", "Java", RequirementPriority.REQUIRED, "Java");
        return new RequirementSet(1L, identity, List.of(), List.of(req1), List.of());
    }

    private RetrievalResult createRetrievalResult() {
        Evidence ev1 = new Evidence("F0001", "R0001", "F0001", "5 years Java", new SourceReference("s", "t", "sec", "Java developer"));
        return new RetrievalResult(Map.of("R0001", List.of(ev1)));
    }

    private AnalysisContext createAnalysisContext() {
        JobContext job = createJobContext();
        CandidateProfile profile = new CandidateProfile(1L, new com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity("u1", 1L), "h", "d", ProfileStatus.READY, List.of(), List.of(), List.of());
        return new AnalysisContext(
            "trace", "task", job, profile, profile.identity(),
            new com.aijob.analyzer.analysis.identity.AnalysisIdentity(new JobFingerprint("f"), new com.aijob.analyzer.analysis.identity.AnalysisDependencyFingerprint("d")),
            com.aijob.analyzer.support.time.ExecutionDeadline.fromNow(java.time.Duration.ofMinutes(1), com.aijob.analyzer.support.time.TimeSource.SYSTEM),
            mock(com.aijob.analyzer.analysis.identity.AnalysisDependencySnapshot.class)
        );
    }
}
