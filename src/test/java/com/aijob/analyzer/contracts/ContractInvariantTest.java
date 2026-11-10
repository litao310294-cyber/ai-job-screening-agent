package com.aijob.analyzer.contracts;

import com.aijob.analyzer.analysis.domain.*;
import com.aijob.analyzer.profile.domain.*;
import com.aijob.analyzer.requirement.domain.*;
import com.aijob.analyzer.workflow.domain.*;
import com.aijob.analyzer.analysis.identity.*;
import com.aijob.analyzer.retrieval.domain.*;
import com.aijob.analyzer.llm.*;
import com.aijob.analyzer.retrieval.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ContractInvariantTest {

    @Test
    void evidenceRefUsesFactIdConvention() {
        var source = new SourceReference("resume-1", "resume", "project", "Redis Lua");
        var e = new Evidence("F0001", "R0001", "F0001", "Redis Lua", source);
        assertEquals(e.factId(), e.evidenceRef());

        // Negative test: evidenceRef != factId
        assertThrows(IllegalArgumentException.class, () ->
            new Evidence("F0002", "R0001", "F0001", "Redis Lua", source)
        );
    }

    @Test
    void profileArtifactIdentityInvariants() {
        assertThrows(IllegalArgumentException.class, () -> new ProfileArtifactIdentity("", 1));
        assertThrows(IllegalArgumentException.class, () -> new ProfileArtifactIdentity(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProfileArtifactIdentity("u1", 0));
        assertThrows(IllegalArgumentException.class, () -> new ProfileArtifactIdentity("u1", -1));
    }

    @Test
    void candidateProfileInvariants() {
        var id = new ProfileArtifactIdentity("u1", 1);
        assertThrows(IllegalArgumentException.class, () ->
            new CandidateProfile(0, id, "f", "p", ProfileStatus.READY, List.of(), List.of(), List.of())
        );
        assertThrows(IllegalArgumentException.class, () ->
            new CandidateProfile(1, null, "f", "p", ProfileStatus.READY, List.of(), List.of(), List.of())
        );
        assertThrows(IllegalArgumentException.class, () ->
            new CandidateProfile(1, id, "", "p", ProfileStatus.READY, List.of(), List.of(), List.of())
        );
        assertThrows(IllegalArgumentException.class, () ->
            new CandidateProfile(1, id, "f", "  ", ProfileStatus.READY, List.of(), List.of(), List.of())
        );
        assertThrows(IllegalArgumentException.class, () ->
            new CandidateProfile(1, id, "f", "p", null, List.of(), List.of(), List.of())
        );
    }

    @Test
    void profileFactInvariants() {
        var source = new SourceReference("s1", "t1", "sec", "text");
        assertThrows(IllegalArgumentException.class, () ->
            new ProfileFact("", 1, "type", "text", source)
        );
        assertThrows(IllegalArgumentException.class, () ->
            new ProfileFact("F0001", 0, "type", "text", source)
        );
    }

    @Test
    void requirementSetInvariants() {
        var id = new RequirementIdentity(new JobFingerprint("j"), new ParserDependencyFingerprint("p"));
        assertThrows(IllegalArgumentException.class, () ->
            new RequirementSet(0, id, List.of(), List.of(), List.of())
        );
        assertThrows(IllegalArgumentException.class, () ->
            new RequirementSet(1, null, List.of(), List.of(), List.of())
        );
    }

    @Test
    void atomicRequirementInvariants() {
        assertThrows(IllegalArgumentException.class, () ->
            new AtomicRequirement("", "type", "text", RequirementPriority.REQUIRED, "source")
        );
        assertThrows(IllegalArgumentException.class, () ->
            new AtomicRequirement("R1", " ", "text", RequirementPriority.REQUIRED, "source")
        );
        assertThrows(IllegalArgumentException.class, () ->
            new AtomicRequirement("R1", "type", null, RequirementPriority.REQUIRED, "source")
        );
        assertThrows(IllegalArgumentException.class, () ->
            new AtomicRequirement("R1", "type", "text", null, "source")
        );
        assertThrows(IllegalArgumentException.class, () ->
            new AtomicRequirement("R1", "type", "text", RequirementPriority.REQUIRED, null)
        );
    }

    @Test
    void analysisArtifactInvariants() {
        var id = new AnalysisIdentity(new JobFingerprint("j"), new AnalysisDependencyFingerprint("a"));
        assertThrows(IllegalArgumentException.class, () ->
            new AnalysisArtifact(0, id, null, null, Instant.now())
        );
    }

    @Test
    void requirementAssessmentEvidenceInvariants() {
        // ID blank check
        assertThrows(IllegalArgumentException.class, () ->
            new RequirementAssessment(" ", RequirementAssessmentStatus.INSUFFICIENT_EVIDENCE, List.of(), "r", "g")
        );

        // SUPPORTED, PARTIALLY_SUPPORTED, UNSUPPORTED require evidence
        assertThrows(IllegalArgumentException.class, () ->
            new RequirementAssessment("R1", RequirementAssessmentStatus.SUPPORTED, List.of(), "r", "g")
        );
        assertThrows(IllegalArgumentException.class, () ->
            new RequirementAssessment("R1", RequirementAssessmentStatus.PARTIALLY_SUPPORTED, List.of(), "r", "g")
        );
        assertThrows(IllegalArgumentException.class, () ->
            new RequirementAssessment("R1", RequirementAssessmentStatus.UNSUPPORTED, List.of(), "r", "g")
        );

        // INSUFFICIENT_EVIDENCE allows empty
        assertDoesNotThrow(() ->
            new RequirementAssessment("R1", RequirementAssessmentStatus.INSUFFICIENT_EVIDENCE, List.of(), "r", "g")
        );
    }

    @Test
    void validatedRequirementPayloadHasNoArtifactId() {
        // Verification by reflection: no getArtifactId or artifactId field
        try {
            ValidatedRequirementPayload.class.getMethod("artifactId");
            fail("ValidatedRequirementPayload should not have artifactId accessor");
        } catch (NoSuchMethodException e) {
            // expected
        }
    }

    @Test
    void candidateProfileDraftPartitions() {
        var draft = new CandidateProfileDraft(List.of(), List.of(), List.of());
        assertNotNull(draft.structuredFacts());
        assertNotNull(draft.retrievalFacts());
        assertNotNull(draft.contextOnly());
    }

    @Test
    void jobRequirementDraftPartitions() {
        var draft = new JobRequirementDraft(List.of(), List.of(), List.of());
        assertNotNull(draft.structuredConstraints());
        assertNotNull(draft.retrievalRequirements());
        assertNotNull(draft.ignoredContext());
    }

    @Test
    void temporaryRequirementIdIsLocal() {
        var draft = new RetrievalRequirementDraft("TEMP-001", "type", "text", RequirementPriority.REQUIRED, "source");
        assertEquals("TEMP-001", draft.temporaryRequirementId());
    }

    @Test
    void ruleResultInvariants() {
        assertThrows(NullPointerException.class, () -> new RuleResult(null, List.of()));
        assertDoesNotThrow(() -> new RuleResult(RuleSignal.CONTINUE, null));
    }

    @Test
    void resultOriginTypes() {
        assertNotNull(ResultOrigin.LLM_ANALYSIS);
        assertNotNull(ResultOrigin.RULE_TERMINAL);
        assertNotNull(ResultOrigin.DEGRADED_FALLBACK);
        assertEquals(3, ResultOrigin.values().length);

        // Confirm distinct semantic types
        assertNotEquals(ResultOrigin.class, WorkflowStatus.class);
        assertNotEquals(ResultOrigin.class, JobDecision.class);
        assertNotEquals(WorkflowStatus.class, JobDecision.class);
    }

    @Test
    void draftFactsLackDurableIdentity() {
        // CandidateProfileDraft facts should not have factId
        try {
            RetrievalFactDraft.class.getMethod("factId");
            fail("RetrievalFactDraft should not have factId");
        } catch (NoSuchMethodException e) {
            // expected
        }
        try {
            StructuredFactDraft.class.getMethod("factId");
            fail("StructuredFactDraft should not have factId");
        } catch (NoSuchMethodException e) {
            // expected
        }
    }

    @Test
    void snapshotStructures() {
        // RetrievalSemanticSnapshot
        var rss = new RetrievalSemanticSnapshot("v1", "a1", "v1", "m1", 20, 20, 60, 5);
        assertEquals("v1", rss.queryBuilderVersion());
        assertEquals(60, rss.rrfK());

        // LlmSemanticSnapshot
        var config = new LlmGenerationConfigSnapshot(java.math.BigDecimal.valueOf(0.7), java.math.BigDecimal.ONE, 1000, "json");
        var lss = new LlmSemanticSnapshot("v1", "m1", "s1", config);
        assertEquals("m1", lss.modelIdentity());
        assertEquals(1000, lss.generationConfig().maxOutputTokens());
    }

    @Test
    void jobAnalysisResultCollectionsNotNull() {
        var res = new JobAnalysisResult(JobDecision.CONSIDER, "o", null, null, null, "r", "s");
        assertNotNull(res.requirementAssessments());
        assertNotNull(res.risks());
        assertNotNull(res.strengths());
    }
}
