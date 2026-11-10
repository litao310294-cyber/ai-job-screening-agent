package com.aijob.analyzer.analysis.identity;

import com.aijob.analyzer.llm.LlmGenerationConfigSnapshot;
import com.aijob.analyzer.llm.LlmSemanticSnapshot;
import com.aijob.analyzer.retrieval.RetrievalSemanticSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AnalysisIdentityFactoryTest {
    private final AnalysisIdentityFactory analysisFactory = new AnalysisIdentityFactory();
    private final RequirementIdentityFactory requirementFactory = new RequirementIdentityFactory();

    @Test
    void profileChangeOnlyChangesAnalysisIdentity() {
        RequirementIdentity requirement = requirementFactory.create(job(), parserSnapshot(BigDecimal.ZERO));
        assertNotEquals(analysisFactory.create(snapshot(13, requirement, retrieval(60), finalSnapshot(BigDecimal.ZERO))),
                analysisFactory.create(snapshot(14, requirement, retrieval(60), finalSnapshot(BigDecimal.ZERO))));
        assertEquals(requirement, requirementFactory.create(job(), parserSnapshot(BigDecimal.ZERO)));
    }

    @Test
    void retrievalAndFinalGenerationChangesChangeAnalysisIdentity() {
        RequirementIdentity requirement = requirementFactory.create(job(), parserSnapshot(BigDecimal.ZERO));
        assertNotEquals(analysisFactory.create(snapshot(13, requirement, retrieval(60), finalSnapshot(BigDecimal.ZERO))),
                analysisFactory.create(snapshot(13, requirement, retrieval(80), finalSnapshot(BigDecimal.ZERO))));
        assertNotEquals(analysisFactory.create(snapshot(13, requirement, retrieval(60), finalSnapshot(BigDecimal.ZERO))),
                analysisFactory.create(snapshot(13, requirement, retrieval(60), finalSnapshot(new BigDecimal("0.7")))));
    }

    @Test
    void parserGenerationChangeChangesRequirementIdentity() {
        assertNotEquals(requirementFactory.create(job(), parserSnapshot(BigDecimal.ZERO)),
                requirementFactory.create(job(), parserSnapshot(new BigDecimal("0.7"))));
    }

    private AnalysisDependencySnapshot snapshot(long profileVersion, RequirementIdentity requirement, RetrievalSemanticSnapshot retrieval, LlmSemanticSnapshot finalSnapshot) {
        return new AnalysisDependencySnapshot(job(), profileVersion, new ProfileArtifactIdentity("user", profileVersion), 7, requirement,
                "rule-v1", analysisFactory.retrievalFingerprint(retrieval), retrieval,
                analysisFactory.finalLlmFingerprint(finalSnapshot), finalSnapshot);
    }
    private JobFingerprint job() { return new JobFingerprint("a".repeat(64)); }
    private RetrievalSemanticSnapshot retrieval(int rrfK) { return new RetrievalSemanticSnapshot("q", "a", "v", "embed", 20, 20, rrfK, 5); }
    private LlmSemanticSnapshot parserSnapshot(BigDecimal temperature) { return new LlmSemanticSnapshot("parser", "model", "schema", new LlmGenerationConfigSnapshot(temperature, BigDecimal.ONE, 100, "json")); }
    private LlmSemanticSnapshot finalSnapshot(BigDecimal temperature) { return new LlmSemanticSnapshot("final", "model", "schema", new LlmGenerationConfigSnapshot(temperature, BigDecimal.ONE, 100, "json")); }
}
