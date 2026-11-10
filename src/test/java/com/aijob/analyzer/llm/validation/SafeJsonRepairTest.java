package com.aijob.analyzer.llm.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:isolated;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false"})
@org.springframework.test.context.ActiveProfiles("test")

class SafeJsonRepairTest {

    @Autowired
    private SafeJsonRepair repairer;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.aijob.analyzer.profile.port.CandidateProfileRepository candidateProfileRepository;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.aijob.analyzer.requirement.port.RequirementSetRepository requirementSetRepository;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.aijob.analyzer.llm.port.ChatModelClient chatModelClient;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.aijob.analyzer.support.retry.RetryExecutor retryExecutor;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.aijob.analyzer.retrieval.ProfileProjectionService profileProjectionService;

    @Test
    @DisplayName("AC-LLM-17: Should repair BOM and outer whitespace")
    void testRepairBomAndWhitespace() {
        String input = "\uFEFF  {\"key\": \"value\"}  ";
        var result = repairer.repair(input);
        assertTrue(result.isPresent());
        assertEquals("{\"key\": \"value\"}", result.get());
    }

    @Test
    @DisplayName("AC-LLM-17: Should repair outer markdown code fence")
    void testRepairMarkdownFence() {
        String input = "```json\n{\"key\": \"value\"}\n```";
        var result = repairer.repair(input);
        assertTrue(result.isPresent());
        assertEquals("{\"key\": \"value\"}", result.get());

        String inputNoLabel = "```\n{\"key\": \"value\"}\n```";
        result = repairer.repair(inputNoLabel);
        assertTrue(result.isPresent());
        assertEquals("{\"key\": \"value\"}", result.get());
    }

    @Test
    @DisplayName("AC-LLM-17: Should repair PARTIAL alias to PARTIALLY_SUPPORTED")
    void testRepairPartialAlias() {
        String input = "{\"status\": \"PARTIAL\"}";
        var result = repairer.repair(input);
        assertTrue(result.isPresent());
        assertEquals("{\"status\": \"PARTIALLY_SUPPORTED\"}", result.get());
    }

    @Test
    @DisplayName("AC-LLM-17: Should NOT repair truncated JSON")
    void testNoRepairTruncatedJson() {
        String input = "{\"key\": \"val";
        var result = repairer.repair(input);
        // Whitelist repair doesn't fix syntax, it just cleans the wrapper or specific aliases
        // If it's still malformed after cleaning, it's the parser's job to fail.
        // But SafeJsonRepair itself should not "fix" it by guessing.
        assertEquals(input, result.get()); // Or however it's implemented, it shouldn't invent content.
    }

    @Test
    @DisplayName("AC-LLM-17: Must not alter a BOM inside JSON content")
    void doesNotAlterBomInsideJsonContent() {
        String input = "{\"key\": \"before\uFEFFafter\"}";

        assertEquals(input, repairer.repair(input).orElseThrow());
    }

    @Test
    @DisplayName("AC-LLM-17: PARTIAL alias applies only to the assessment status field")
    void doesNotRewriteUnrelatedPartialText() {
        String input = "{\"summary\": \"PARTIAL\", \"status\": \"PARTIAL\"}";

        assertEquals(
                "{\"summary\": \"PARTIAL\", \"status\": \"PARTIALLY_SUPPORTED\"}",
                repairer.repair(input).orElseThrow()
        );
    }
}
