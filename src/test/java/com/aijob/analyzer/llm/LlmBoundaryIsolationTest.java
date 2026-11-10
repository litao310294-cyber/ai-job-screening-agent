package com.aijob.analyzer.llm;

import com.aijob.analyzer.analysis.domain.*;
import com.aijob.analyzer.analysis.identity.*;
import com.aijob.analyzer.llm.port.ChatModelRequest;
import com.aijob.analyzer.llm.port.ChatModelResponse;
import com.aijob.analyzer.llm.validation.JsonContractParser;
import com.aijob.analyzer.llm.validation.SafeJsonRepair;
import com.aijob.analyzer.profile.domain.*;
import com.aijob.analyzer.profile.repository.InMemoryCandidateProfileRepository;
import com.aijob.analyzer.profile.validation.CandidateProfileDraftValidator;
import com.aijob.analyzer.requirement.domain.*;
import com.aijob.analyzer.requirement.validation.JobRequirementDraftValidator;
import com.aijob.analyzer.analysis.validation.JobAnalysisResultValidator;
import com.aijob.analyzer.retrieval.domain.Evidence;
import com.aijob.analyzer.retrieval.domain.RetrievalResult;
import com.aijob.analyzer.support.ScriptedChatModelClient;
import com.aijob.analyzer.workflow.domain.RuleResult;
import com.aijob.analyzer.workflow.domain.RuleSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

public class LlmBoundaryIsolationTest {

    private ScriptedChatModelClient chatModelClient;
    private ResumeParserService resumeParserService;
    private RequirementParserService requirementParserService;
    private FinalAnalysisService finalAnalysisService;
    private JsonContractParser jsonParser;
    private SafeJsonRepair jsonRepairer;
    private CandidateProfileDraftValidator resumeValidator;
    private JobRequirementDraftValidator reqValidator;
    private JobAnalysisResultValidator finalValidator;

    @BeforeEach
    void setUp() {
        chatModelClient = new ScriptedChatModelClient();
        jsonParser = Mockito.mock(JsonContractParser.class);
        jsonRepairer = Mockito.mock(SafeJsonRepair.class);
        resumeValidator = Mockito.mock(CandidateProfileDraftValidator.class);
        reqValidator = Mockito.mock(JobRequirementDraftValidator.class);
        finalValidator = Mockito.mock(JobAnalysisResultValidator.class);

        when(jsonRepairer.repair(anyString())).thenReturn(Optional.empty());

        resumeParserService = new ResumeParserService(
                chatModelClient,
                jsonParser,
                jsonRepairer,
                resumeValidator,
                new com.aijob.analyzer.profile.ProfileFactIdAssigner()
        );
        requirementParserService = new RequirementParserService(
                chatModelClient,
                jsonParser,
                jsonRepairer,
                reqValidator,
                new com.aijob.analyzer.requirement.RequirementIdAssigner(),
                new com.aijob.analyzer.requirement.RequirementNormalizer()
        );
        finalAnalysisService = new FinalAnalysisService(
                chatModelClient,
                jsonParser,
                jsonRepairer,
                finalValidator
        );

        // Inject prompt resources
        ReflectionTestUtils.setField(resumeParserService, "resumeParserPromptResource", new org.springframework.core.io.ByteArrayResource("resume prompt".getBytes()));
        ReflectionTestUtils.setField(requirementParserService, "requirementParserPromptResource", new org.springframework.core.io.ByteArrayResource("req prompt".getBytes()));
        ReflectionTestUtils.setField(finalAnalysisService, "finalAnalysisPromptResource", new org.springframework.core.io.ByteArrayResource("final prompt".getBytes()));
    }

    @Test
    @DisplayName("Boundary: Resume Parser only sees RawResume text")
    void resume_parser_isolation() {
        chatModelClient.addScript(new ChatModelResponse("{}", "req1", "m1", "v1", 10, 20, "stop"));
        RawResume rawResume = new RawResume("u1", "s1", "t1", "RAW_RESUME_CONTENT", "h1");

        when(jsonParser.parse(anyString(), eq(CandidateProfileDraft.class)))
                .thenReturn(new CandidateProfileDraft(List.of(), List.of(), List.of()));

        resumeParserService.parse(rawResume, new ProfileArtifactIdentity("u1", 1), new LlmExecutionContext(null, 1, 0), null, null);

        ChatModelRequest lastRequest = chatModelClient.getLastRequest();
        assertEquals("RAW_RESUME_CONTENT", lastRequest.userPrompt());
        assertFalse(lastRequest.userPrompt().contains("Job"));
        assertFalse(lastRequest.userPrompt().contains("Requirement"));
    }

    @Test
    @DisplayName("Boundary: Requirement Parser sees structured Job and original JD, without resume")
    void requirement_parser_isolation() {
        chatModelClient.addScript(new ChatModelResponse("{}", "req1", "m1", "v1", 10, 20, "stop"));
        JobContext job = new JobContext("p", "j", "T", "C", 1, "JOB_SUMMARY", "S", "JOB_DESC_CONTENT", new JobFingerprint("f"));

        when(jsonParser.parse(anyString(), eq(JobRequirementDraft.class)))
                .thenReturn(new JobRequirementDraft(List.of(), List.of(), List.of()));

        requirementParserService.parseAndValidate(job, new RequirementIdentity(new JobFingerprint("f"), new ParserDependencyFingerprint("p")), new LlmExecutionContext(null, 1, 0), null, null);

        ChatModelRequest lastRequest = chatModelClient.getLastRequest();
        assertEquals(job.analysisText(), lastRequest.userPrompt());
        assertTrue(lastRequest.userPrompt().contains("Original JD:\nJOB_DESC_CONTENT"));
        assertFalse(lastRequest.userPrompt().contains("Resume"));
        assertFalse(lastRequest.userPrompt().contains("Fact"));
    }

    @Test
    @DisplayName("Boundary: Final Analysis minimum context (Job, Rule, ReqSet, Facts, Evidence)")
    void final_analysis_isolation() {
        chatModelClient.addScript(new ChatModelResponse("{}", "req1", "m1", "v1", 10, 20, "stop"));

        JobContext job = new JobContext("p", "j", "T", "C", 1, "JOB_SUMMARY", "S", "JOB_DESC_CONTENT", new JobFingerprint("f"));
        RuleResult rule = new RuleResult(RuleSignal.CONTINUE, List.of("RULE_REASON"));
        AtomicRequirement req = new AtomicRequirement("R0001", "SKILL", "Java Normalized", RequirementPriority.REQUIRED, "Java text");
        RequirementSet reqSet = new RequirementSet(1L, new RequirementIdentity(new JobFingerprint("f"), new ParserDependencyFingerprint("p")), List.of(), List.of(req), List.of());
        StructuredFact fact = new StructuredFact("SKILL", "Java Structured", new SourceReference("s", "t", "sec", "source"));
        Evidence evidence = new Evidence("F0001", "R0001", "F0001", "EVIDENCE_CONTENT", new SourceReference("s", "t", "sec", "source"));
        RetrievalResult retrievalResult = new RetrievalResult(Map.of("R0001", List.of(evidence)));

        finalAnalysisService.generateStructured(job, rule, reqSet, List.of(fact), retrievalResult, new LlmExecutionContext(null, 1, 0), null, null);

        ChatModelRequest lastRequest = chatModelClient.getLastRequest();
        String prompt = lastRequest.userPrompt();

        assertTrue(prompt.contains("JOB_DESC_CONTENT"));
        assertTrue(prompt.contains("RULE_REASON"));
        assertTrue(prompt.contains("Java Normalized"));
        assertTrue(prompt.contains("EVIDENCE_CONTENT"));
        assertTrue(prompt.contains("Candidate Structured Facts:"));
        assertTrue(prompt.contains("SKILL: Java Structured"));

        // Negative checks
        assertFalse(prompt.contains("RAW_RESUME_CONTENT")); // No RawResume
        assertFalse(prompt.contains("BM25")); // No internal scores
    }

    @Test
    @DisplayName("RT-12: Monotonic Version Publish in InMemory Repository")
    void monotonic_publish_logic() {
        InMemoryCandidateProfileRepository repo = new InMemoryCandidateProfileRepository();
        ProfileArtifactIdentity p100 = new ProfileArtifactIdentity("U1", 100);
        ProfileArtifactIdentity p101 = new ProfileArtifactIdentity("U1", 101);

        // P101 finishes first
        assertEquals(ProfilePublishOutcome.PUBLISHED_CURRENT, repo.markReadyAndPublishCurrent(p101));
        assertEquals(p101, repo.findCurrentReady("U1").get());

        // P100 finishes later -> should NOT become current
        assertEquals(ProfilePublishOutcome.READY_NOT_CURRENT, repo.markReadyAndPublishCurrent(p100));
        assertEquals(p101, repo.findCurrentReady("U1").get());

        // P102 finishes -> becomes current
        ProfileArtifactIdentity p102 = new ProfileArtifactIdentity("U1", 102);
        assertEquals(ProfilePublishOutcome.PUBLISHED_CURRENT, repo.markReadyAndPublishCurrent(p102));
        assertEquals(p102, repo.findCurrentReady("U1").get());
    }

    private <T> T mock(Class<T> type) {
        return org.mockito.Mockito.mock(type);
    }
}
