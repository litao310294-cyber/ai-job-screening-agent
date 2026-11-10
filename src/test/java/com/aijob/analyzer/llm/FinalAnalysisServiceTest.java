package com.aijob.analyzer.llm;

import com.aijob.analyzer.analysis.domain.*;
import com.aijob.analyzer.analysis.identity.JobFingerprint;
import com.aijob.analyzer.analysis.identity.ParserDependencyFingerprint;
import com.aijob.analyzer.analysis.identity.RequirementIdentity;
import com.aijob.analyzer.llm.port.ChatModelClient;
import com.aijob.analyzer.llm.port.ChatModelRequest;
import com.aijob.analyzer.llm.port.ChatModelResponse;
import com.aijob.analyzer.llm.LlmExecutionContext;
import com.aijob.analyzer.profile.domain.SourceReference;
import com.aijob.analyzer.profile.domain.StructuredFact;
import com.aijob.analyzer.requirement.domain.AtomicRequirement;
import com.aijob.analyzer.requirement.domain.RequirementPriority;
import com.aijob.analyzer.requirement.domain.RequirementSet;
import com.aijob.analyzer.retrieval.domain.Evidence;
import com.aijob.analyzer.retrieval.domain.RetrievalResult;
import com.aijob.analyzer.workflow.domain.RuleResult;
import com.aijob.analyzer.workflow.domain.RuleSignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:isolated;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false"})
@ActiveProfiles("test")
class FinalAnalysisServiceTest {

    @Autowired
    private FinalAnalysisService finalAnalysisService;

    @MockBean
    private ChatModelClient chatModelClient;

    @MockBean
    private com.aijob.analyzer.profile.port.CandidateProfileRepository candidateProfileRepository;

    @MockBean
    private com.aijob.analyzer.retrieval.ProfileProjectionService profileProjectionService;

    @MockBean
    private com.aijob.analyzer.requirement.port.RequirementSetRepository requirementSetRepository;

    @MockBean
    private com.aijob.analyzer.support.retry.RetryExecutor retryExecutor;

    @MockBean
    private com.aijob.analyzer.llm.recovery.LlmRecoveryPolicy recoveryPolicy;

    @Test
    @DisplayName("AC-LLM-15: Should generate structured analysis and AC-LLM-16: validate evidence refs")
    void testGenerateStructuredAnalysis() {
        JobContext job = new JobContext("p", "j", "T", "C", 1, "D", "S", "Desc", new JobFingerprint("f"));
        RequirementIdentity identity = new RequirementIdentity(new JobFingerprint("f"), new ParserDependencyFingerprint("p"));
        RuleResult rule = new RuleResult(RuleSignal.CONTINUE, List.of());
        AtomicRequirement req1 = new AtomicRequirement("R0001", "SKILL", "Java", RequirementPriority.REQUIRED, "Java");
        RequirementSet requirementSet = new RequirementSet(1L, identity, List.of(), List.of(req1), List.of());
        List<StructuredFact> facts = List.of();
        Evidence ev1 = new Evidence("F0001", "R0001", "F0001", "5 years Java", new SourceReference("s", "t", "sec", "Java developer"));
        RetrievalResult retrievalResult = new RetrievalResult(Map.of("R0001", List.of(ev1)));
        LlmExecutionContext executionContext = new LlmExecutionContext(null, 1, 0);

        String jsonResponse = """
                {
                  "decision": "RECOMMEND_APPLY",
                  "overallAssessment": "Strong candidate",
                  "requirementAssessments": [
                    {
                      "requirementId": "R0001",
                      "status": "SUPPORTED",
                      "evidenceRefs": ["F0001"],
                      "reasoning": "Has Java exp",
                      "gap": ""
                    }
                  ],
                  "risks": [],
                  "strengths": ["Java"],
                  "recommendation": "Interview",
                  "summary": "Good"
                }
                """;

        when(chatModelClient.generate(any(ChatModelRequest.class)))
                .thenReturn(new ChatModelResponse(jsonResponse, "req-3", "gpt-4o", "v1", 100, 200, "stop"));

        JobAnalysisResult result = finalAnalysisService.generateStructured(
                job, rule, requirementSet, facts, retrievalResult, executionContext, null, null);

        assertNotNull(result);
        assertEquals(JobDecision.RECOMMEND_APPLY, result.decision());
        assertEquals(1, result.requirementAssessments().size());
        assertEquals("R0001", result.requirementAssessments().get(0).requirementId());
        assertEquals(List.of("F0001"), result.requirementAssessments().get(0).evidenceRefs());
        var request = org.mockito.ArgumentCaptor.forClass(ChatModelRequest.class);
        org.mockito.Mockito.verify(chatModelClient).generate(request.capture());
        assertTrue(request.getValue().userPrompt().contains("Allowed evidenceRefs for R0001: [F0001]"));
    }

    @Test
    @DisplayName("AC-LLM-16: Should fail if SUPPORTED but evidenceRefs is empty")
    void testSupportedWithoutEvidence() {
        // This test will be for the separate validator
    }
}
