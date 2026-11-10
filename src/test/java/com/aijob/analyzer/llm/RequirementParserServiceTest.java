package com.aijob.analyzer.llm;

import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.analysis.identity.JobFingerprint;
import com.aijob.analyzer.analysis.identity.ParserDependencyFingerprint;
import com.aijob.analyzer.analysis.identity.RequirementIdentity;
import com.aijob.analyzer.llm.port.ChatModelClient;
import com.aijob.analyzer.llm.port.ChatModelRequest;
import com.aijob.analyzer.llm.port.ChatModelResponse;
import com.aijob.analyzer.llm.LlmExecutionContext;
import com.aijob.analyzer.requirement.domain.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:isolated;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false"})
@ActiveProfiles("test")
class RequirementParserServiceTest {

    @Autowired
    private RequirementParserService requirementParserService;

    @MockBean
    private ChatModelClient chatModelClient;

    @MockBean
    private com.aijob.analyzer.requirement.port.RequirementSetRepository requirementSetRepository;

    @MockBean
    private com.aijob.analyzer.profile.port.CandidateProfileRepository candidateProfileRepository;

    @MockBean
    private com.aijob.analyzer.support.retry.RetryExecutor retryExecutor;

    @MockBean
    private com.aijob.analyzer.retrieval.ProfileProjectionService profileProjectionService;

    @Test
    @DisplayName("AC-LLM-14: Should parse job context and assign R IDs")
    void testParseValidRequirements() {
        JobContext job = new JobContext("platform", "j-1", "Java Dev", "Shanghai", 3, "6 months", "20k", "Need Java and Redis.", new JobFingerprint("f1"));
        RequirementIdentity identity = new RequirementIdentity(new JobFingerprint("f1"), new ParserDependencyFingerprint("p1"));
        LlmExecutionContext executionContext = new LlmExecutionContext(null, 1, 0);

        String jsonResponse = """
                {
                  "structuredConstraints": [
                    {
                      "type": "CITY",
                      "operator": "EQUALS",
                      "value": "Shanghai",
                      "sourceText": "Shanghai"
                    }
                  ],
                  "retrievalRequirements": [
                    {
                      "temporaryRequirementId": "temp-1",
                      "requirementType": "SKILL",
                      "normalizedText": "Java",
                      "priority": "REQUIRED",
                      "sourceText": "Java"
                    },
                    {
                      "temporaryRequirementId": "temp-2",
                      "requirementType": "SKILL",
                      "normalizedText": "Redis",
                      "priority": "PREFERRED",
                      "sourceText": "Redis"
                    }
                  ],
                  "ignoredContext": []
                }
                """;

        when(chatModelClient.generate(any(ChatModelRequest.class)))
                .thenReturn(new ChatModelResponse(jsonResponse, "req-2", "gpt-4o", "v1", 100, 200, "stop"));

        ValidatedRequirementPayload payload = requirementParserService.parseAndValidate(job, identity, executionContext, null, null);

        assertNotNull(payload);
        var sent = org.mockito.ArgumentCaptor.forClass(ChatModelRequest.class);
        org.mockito.Mockito.verify(chatModelClient).generate(sent.capture());
        assertTrue(sent.getValue().systemPrompt().contains("All three arrays are mandatory"));
        assertEquals(1, payload.structuredConstraints().size());
        assertEquals(2, payload.retrievalRequirements().size());
        assertEquals("R0001", payload.retrievalRequirements().get(0).requirementId());
        assertEquals("R0002", payload.retrievalRequirements().get(1).requirementId());
        assertEquals("Java", payload.retrievalRequirements().get(0).sourceText());
    }

    @Test
    @DisplayName("AC-LLM-05: Should fail grounding if source text is not in job context")
    void testGroundingFailure() {
        JobContext job = new JobContext("platform", "j-1", "Java Dev", "Shanghai", 3, "6 months", "20k", "Need Java.", new JobFingerprint("f2"));
        RequirementIdentity identity = new RequirementIdentity(new JobFingerprint("f2"), new ParserDependencyFingerprint("p2"));
        LlmExecutionContext executionContext = new LlmExecutionContext(null, 1, 0);

        // LLM hallucinate "Redis"
        String jsonResponse = """
                {
                  "structuredConstraints": [],
                  "retrievalRequirements": [
                    {
                      "temporaryRequirementId": "temp-1",
                      "requirementType": "SKILL",
                      "normalizedText": "Redis",
                      "priority": "REQUIRED",
                      "sourceText": "Redis experience"
                    }
                  ],
                  "ignoredContext": []
                }
                """;

        when(chatModelClient.generate(any(ChatModelRequest.class)))
                .thenReturn(new ChatModelResponse(jsonResponse, "req-2", "gpt-4o", "v1", 100, 200, "stop"));

        assertThrows(RuntimeException.class, () -> requirementParserService.parseAndValidate(job, identity, executionContext, null, null));
    }
}
