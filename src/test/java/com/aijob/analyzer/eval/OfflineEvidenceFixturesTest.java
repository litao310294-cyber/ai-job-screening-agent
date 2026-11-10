package com.aijob.analyzer.eval;

import com.aijob.analyzer.analysis.identity.AnalysisIdentityFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OfflineEvidenceFixturesTest {
    @Test
    void fixturesAreStableAndProviderFree() {
        var versions = OfflineEvidenceFixtures.versions();
        var profile = OfflineEvidenceFixtures.profile("offline-user", 1L);
        var requirements = OfflineEvidenceFixtures.requirementSet(true);
        var snapshot = OfflineEvidenceFixtures.snapshot(profile, requirements, new AnalysisIdentityFactory(), versions);

        assertEquals("offline-model", versions.getFinalAnalysis().getModel());
        assertEquals("R0001", requirements.retrievalRequirements().getFirst().requirementId());
        assertEquals("F0001", OfflineEvidenceFixtures.retrievalResult(true)
                .evidenceByRequirement().get("R0001").getFirst().evidenceRef());
        assertNotNull(snapshot.finalLlmSemanticFingerprint());
        assertFalse(OfflineEvidenceFixtures.validJson(true).contains("api_key"));
    }
}
