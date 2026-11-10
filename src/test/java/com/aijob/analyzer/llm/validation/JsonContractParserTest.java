package com.aijob.analyzer.llm.validation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:isolated;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false"})
@org.springframework.test.context.ActiveProfiles("test")

class JsonContractParserTest {

    @Autowired
    private JsonContractParser parser;

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

    public record TestRecord(
            @NotBlank String name,
            @NotNull Integer age,
            List<String> tags
    ) {}

    public enum Status { SUCCESS, FAILED }
    public record EnumRecord(Status status) {}

    @Test
    @DisplayName("AC-LLM-01: Should fail on invalid JSON")
    void testParseInvalidJson() {
        String input = "not a json";
        assertThrows(JsonParseException.class, () -> parser.parse(input, TestRecord.class));
    }

    @Test
    @DisplayName("AC-LLM-04: Should fail on missing major semantic fields (Schema Validation)")
    void testParseMissingFields() {
        String input = "{\"name\": \"test\"}"; // age is missing
        assertThrows(SchemaValidationError.class, () -> parser.parse(input, TestRecord.class));
    }

    @Test
    @DisplayName("Should parse valid JSON")
    void testParseValidJson() {
        String input = "{\"name\": \"test\", \"age\": 25, \"tags\": [\"a\", \"b\"]}";
        TestRecord record = parser.parse(input, TestRecord.class);
        assertEquals("test", record.name());
        assertEquals(25, record.age());
        assertEquals(List.of("a", "b"), record.tags());
    }

    @Test
    @DisplayName("AC-LLM-03: Should fail on unknown enum without approved alias")
    void testParseUnknownEnum() {
        String input = "{\"status\": \"UNKNOWN\"}";
        assertThrows(SchemaValidationError.class, () -> parser.parse(input, EnumRecord.class));
    }
}
