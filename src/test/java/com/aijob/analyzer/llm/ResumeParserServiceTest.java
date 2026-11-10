package com.aijob.analyzer.llm;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.llm.port.ChatModelClient;
import com.aijob.analyzer.llm.port.ChatModelRequest;
import com.aijob.analyzer.llm.port.ChatModelResponse;
import com.aijob.analyzer.llm.validation.JsonContractParser;
import com.aijob.analyzer.profile.domain.*;
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
class ResumeParserServiceTest {

    @Autowired
    private ResumeParserService resumeParserService;

    @MockBean
    private ChatModelClient chatModelClient;

    @MockBean
    private com.aijob.analyzer.profile.port.CandidateProfileRepository candidateProfileRepository;

    @MockBean
    private com.aijob.analyzer.requirement.port.RequirementSetRepository requirementSetRepository;

    @MockBean
    private com.aijob.analyzer.retrieval.ProfileProjectionService profileProjectionService;

    @MockBean
    private com.aijob.analyzer.support.retry.RetryExecutor retryExecutor;

    @Test
    @DisplayName("AC-LLM-13: Should parse valid resume and assign F IDs to retrieval facts")
    void testParseValidResume() {
        RawResume rawResume = new RawResume("u-1", "s-1", "My Resume", "Java developer with 5 years experience.", "fingerprint");
        ProfileArtifactIdentity identity = new ProfileArtifactIdentity("u-1", 1L);
        LlmExecutionContext executionContext = new LlmExecutionContext(null, 1, 0);

        String jsonResponse = """
                {
                  "structuredFacts": [],
                  "retrievalFacts": [
                    {
                      "factType": "EXPERIENCE",
                      "normalizedText": "5 years of Java experience",
                      "source": {
                        "sourceId": "s-1",
                        "sourceTitle": "My Resume",
                        "sourceSection": "Summary",
                        "sourceText": "Java developer with 5 years experience"
                      }
                    }
                  ],
                  "contextOnly": []
                }
                """;

        when(chatModelClient.generate(any(ChatModelRequest.class)))
                .thenReturn(new ChatModelResponse(jsonResponse, "req-1", "gpt-4o", "v1", 100, 200, "stop"));

        CandidateProfile profile = resumeParserService.parse(rawResume, identity, executionContext, null, null);

        assertNotNull(profile);
        assertEquals(ProfileStatus.READY, profile.status());
        assertEquals(1, profile.retrievalFacts().size());
        assertEquals("F0001", profile.retrievalFacts().get(0).factId());
        assertEquals("Java developer with 5 years experience", profile.retrievalFacts().get(0).source().sourceText());
    }

    @Test
    @DisplayName("AC-LLM-05: Should fail grounding if source text is not in raw resume")
    void testGroundingFailure() {
        RawResume rawResume = new RawResume("u-1", "s-1", "My Resume", "I only know Python.", "fingerprint");
        ProfileArtifactIdentity identity = new ProfileArtifactIdentity("u-1", 1L);
        LlmExecutionContext executionContext = new LlmExecutionContext(null, 1, 0);

        // LLM hallucinate "Java"
        String jsonResponse = """
                {
                  "structuredFacts": [],
                  "retrievalFacts": [
                    {
                      "factType": "EXPERIENCE",
                      "normalizedText": "Java developer",
                      "source": {
                        "sourceId": "s-1",
                        "sourceTitle": "My Resume",
                        "sourceSection": "Summary",
                        "sourceText": "Expert in Java"
                      }
                    }
                  ],
                  "contextOnly": []
                }
                """;

        when(chatModelClient.generate(any(ChatModelRequest.class)))
                .thenReturn(new ChatModelResponse(jsonResponse, "req-1", "gpt-4o", "v1", 100, 200, "stop"));

        assertThrows(RuntimeException.class, () -> resumeParserService.parse(rawResume, identity, executionContext, null, null));
    }
}
