package com.aijob.analyzer.llm.validation;

import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisContractRegressionTest {
    private final ObjectMapper json = new ObjectMapper();
    private final JsonContractParser parser = new DefaultJsonContractParser(json, Validation.buildDefaultValidatorFactory().getValidator());
    private final String valid = """
            {"decision":"INSUFFICIENT_EVIDENCE","overallAssessment":"Evidence missing",
            "requirementAssessments":[{"requirementId":"R0001","status":"INSUFFICIENT_EVIDENCE",
            "evidenceRefs":[],"reasoning":"No relevant evidence","gap":"missing"}],
            "risks":["Missing evidence"],"strengths":[],"recommendation":"Confirm","summary":"Confirm"}
            """;
    @Test void acceptsExplicitInsufficientEvidenceWithoutGuessingReferences() {
        assertEquals("INSUFFICIENT_EVIDENCE", parser.parse(valid, JobAnalysisResult.class).requirementAssessments().getFirst().status().name());
    }
    @Test void rejectsMissingNullAndEmptyStatuses() {
        for (String replacement : new String[]{"", "\"status\":null,", "\"status\":\"\","}) {
            assertThrows(SchemaValidationError.class, () -> parser.parse(valid.replace("\"status\":\"INSUFFICIENT_EVIDENCE\",", replacement), JobAnalysisResult.class));
        }
    }
    @Test void rejectsMissingNullOrIncorrectRiskArrays() {
        for (String replacement : new String[]{"", "\"risks\":null,", "\"risks\":{},", "\"risks\":[{}],", "\"risks\":[1],"}) {
            assertThrows(SchemaValidationError.class, () -> parser.parse(valid.replace("\"risks\":[\"Missing evidence\"],", replacement), JobAnalysisResult.class), replacement);
        }
    }
    @Test void rejectsNullAssessmentArraysAndEvidenceArrays() {
        assertThrows(SchemaValidationError.class, () -> parser.parse(valid.replace("\"evidenceRefs\":[]", "\"evidenceRefs\":null"), JobAnalysisResult.class));
        assertThrows(SchemaValidationError.class, () -> parser.parse(valid.replace("\"requirementAssessments\":[", "\"requirementAssessments\":null,\"unused\":["), JobAnalysisResult.class));
    }
    @Test void rejectsMissingAndDuplicateRequirementAssessments() {
        var validator = new com.aijob.analyzer.analysis.validation.JobAnalysisResultValidator();
        var requirements = new com.aijob.analyzer.requirement.domain.RequirementSet(1,
                new com.aijob.analyzer.analysis.identity.RequirementIdentity(new com.aijob.analyzer.analysis.identity.JobFingerprint("job"),
                        new com.aijob.analyzer.analysis.identity.ParserDependencyFingerprint("parser")), java.util.List.of(),
                java.util.List.of(new com.aijob.analyzer.requirement.domain.AtomicRequirement("R0001", "SKILL", "Java",
                        com.aijob.analyzer.requirement.domain.RequirementPriority.REQUIRED, "Java")), java.util.List.of());
        var retrieval = new com.aijob.analyzer.retrieval.domain.RetrievalResult(java.util.Map.of());
        var result = parser.parse(valid, JobAnalysisResult.class);
        var missing = new JobAnalysisResult(result.decision(), result.overallAssessment(), java.util.List.of(), result.risks(), result.strengths(), result.recommendation(), result.summary());
        var duplicate = new JobAnalysisResult(result.decision(), result.overallAssessment(), java.util.List.of(result.requirementAssessments().getFirst(), result.requirementAssessments().getFirst()), result.risks(), result.strengths(), result.recommendation(), result.summary());
        assertThrows(BusinessValidationError.class, () -> validator.validate(missing, requirements, retrieval));
        assertThrows(BusinessValidationError.class, () -> validator.validate(duplicate, requirements, retrieval));
        assertDoesNotThrow(() -> validator.validate(result, requirements, retrieval));
    }
    @Test void rejectsInvalidRequirementArraysBeforeNormalization() {
        assertThrows(SchemaValidationError.class, () -> parser.parse("{\"structuredConstraints\":[],\"retrievalRequirements\":null,\"ignoredContext\":[]}", com.aijob.analyzer.requirement.domain.JobRequirementDraft.class));
        assertThrows(SchemaValidationError.class, () -> parser.parse("{\"structuredConstraints\":{},\"retrievalRequirements\":[],\"ignoredContext\":[]}", com.aijob.analyzer.requirement.domain.JobRequirementDraft.class));
    }
}
