package com.aijob.analyzer.demo;

import com.aijob.analyzer.llm.*;
import com.aijob.analyzer.llm.port.*;
import com.aijob.analyzer.llm.validation.*;
import com.aijob.analyzer.profile.*;
import com.aijob.analyzer.profile.domain.*;
import com.aijob.analyzer.profile.validation.CandidateProfileDraftValidator;
import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResumeQuoteContractTest {
    private CandidateProfile parse(String quote) throws Exception {
        var json = new ObjectMapper();
        var draft = new CandidateProfileDraft(List.of(), List.of(new RetrievalFactDraft("SKILL", "Java backend",
                new SourceReference("resume", "resume", "skills", quote))), List.of());
        String response = json.writeValueAsString(draft);
        ChatModelClient provider = request -> new ChatModelResponse(response,"TEST","TEST",null,1,1,"stop");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var parser = new ResumeParserService(provider, new DefaultJsonContractParser(json, factory.getValidator()),
                    input -> Optional.empty(), new CandidateProfileDraftValidator(), new ProfileFactIdAssigner());
            var snapshot = new LlmSemanticSnapshot("TEST", "TEST", "TEST", new LlmGenerationConfigSnapshot(BigDecimal.ZERO, BigDecimal.ONE, 1000, "json_object"));
            return parser.parseWithPrompt(new RawResume("TEST", "resume", "resume", "Java 后\r\n端开发", "TEST"),
                    new ProfileArtifactIdentity("TEST",1), new LlmExecutionContext(snapshot,1,0),null,null,"TEST");
        }
    }
    @Test void whitespaceOnlyQuoteRestoresExactOriginalSpan() throws Exception {
        assertEquals("Java 后\r\n端开发", parse("Java 后端开发").retrievalFacts().getFirst().source().sourceText());
    }
    @Test void changedNonWhitespaceTextStillFailsGrounding() {
        assertThrows(GroundingError.class, () -> parse("Java 高并发生产开发"));
    }
}
