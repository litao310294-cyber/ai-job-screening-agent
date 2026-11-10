package com.aijob.analyzer.analysis.identity;

import com.aijob.analyzer.llm.LlmSemanticSnapshot;
import com.aijob.analyzer.llm.LlmGenerationConfigSnapshot;
import com.aijob.analyzer.retrieval.RetrievalSemanticSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class IdentityAcceptanceTest {

    private final RequirementIdentityFactory requirementFactory = new RequirementIdentityFactory();
    private final AnalysisIdentityFactory analysisFactory = new AnalysisIdentityFactory();

    @Test
    void acCp08_profileVersionChange_requirementIdentityUnchanged_analysisIdentityChanges() {
        JobFingerprint job = new JobFingerprint("job1");
        LlmSemanticSnapshot parserSnapshot = new LlmSemanticSnapshot("v1", "gpt-4", "s1", null);
        RequirementIdentity reqId = requirementFactory.create(job, parserSnapshot);

        AnalysisDependencySnapshot snap1 = createSnapshot(job, reqId, 1L);
        AnalysisDependencySnapshot snap2 = createSnapshot(job, reqId, 2L);

        AnalysisIdentity id1 = analysisFactory.create(snap1);
        AnalysisIdentity id2 = analysisFactory.create(snap2);

        assertEquals(reqId, snap1.requirementSetIdentity());
        assertEquals(reqId, snap2.requirementSetIdentity());
        assertNotEquals(id1, id2);
    }

    @Test
    void acCp09_finalPromptVersionChange_requirementReusable_analysisIdentityChanges() {
        JobFingerprint job = new JobFingerprint("job1");
        LlmSemanticSnapshot parserSnapshot = new LlmSemanticSnapshot("v1", "gpt-4", "s1", null);
        RequirementIdentity reqId = requirementFactory.create(job, parserSnapshot);

        AnalysisDependencySnapshot snap1 = createSnapshot(job, reqId, 1L, "prompt_v1");
        AnalysisDependencySnapshot snap2 = createSnapshot(job, reqId, 1L, "prompt_v2");

        AnalysisIdentity id1 = analysisFactory.create(snap1);
        AnalysisIdentity id2 = analysisFactory.create(snap2);

        assertNotEquals(id1, id2);
    }

    @Test
    void acCp17_retrievalSemanticsChange_analysisIdentityChanges() {
        JobFingerprint job = new JobFingerprint("job1");
        RequirementIdentity reqId = requirementFactory.create(job, new LlmSemanticSnapshot("v1", "gpt-4", "s1", null));

        AnalysisDependencySnapshot snap1 = createSnapshot(job, reqId, 1L, "p1", "ret_v1");
        AnalysisDependencySnapshot snap2 = createSnapshot(job, reqId, 1L, "p1", "ret_v2");

        AnalysisIdentity id1 = analysisFactory.create(snap1);
        AnalysisIdentity id2 = analysisFactory.create(snap2);

        assertNotEquals(id1, id2);
    }

    @Test
    void acCp18_finalLlmGenerationConfigChange_analysisIdentityChanges() {
        JobFingerprint job = new JobFingerprint("job1");
        RequirementIdentity reqId = requirementFactory.create(job, new LlmSemanticSnapshot("v1", "gpt-4", "s1", null));

        LlmSemanticSnapshot llm1 = new LlmSemanticSnapshot("p1", "gpt-4", "s1", new LlmGenerationConfigSnapshot(new BigDecimal("0.7"), null, null, null));
        LlmSemanticSnapshot llm2 = new LlmSemanticSnapshot("p1", "gpt-4", "s1", new LlmGenerationConfigSnapshot(new BigDecimal("0.9"), null, null, null));

        AnalysisDependencySnapshot snap1 = createSnapshot(job, reqId, 1L, llm1);
        AnalysisDependencySnapshot snap2 = createSnapshot(job, reqId, 1L, llm2);

        AnalysisIdentity id1 = analysisFactory.create(snap1);
        AnalysisIdentity id2 = analysisFactory.create(snap2);

        assertNotEquals(id1, id2);
    }

    @Test
    void acCp19_parserGenerationSemanticsChange_requirementIdentityChanges() {
        JobFingerprint job = new JobFingerprint("job1");

        LlmSemanticSnapshot p1 = new LlmSemanticSnapshot("p1", "gpt-4", "s1", new LlmGenerationConfigSnapshot(new BigDecimal("0.7"), null, null, null));
        LlmSemanticSnapshot p2 = new LlmSemanticSnapshot("p1", "gpt-4", "s1", new LlmGenerationConfigSnapshot(new BigDecimal("0.9"), null, null, null));

        RequirementIdentity id1 = requirementFactory.create(job, p1);
        RequirementIdentity id2 = requirementFactory.create(job, p2);

        assertNotEquals(id1, id2);
    }

    private AnalysisDependencySnapshot createSnapshot(JobFingerprint job, RequirementIdentity reqId, long profileVersion) {
        return createSnapshot(job, reqId, profileVersion, "prompt_v1", "ret_v1");
    }

    private AnalysisDependencySnapshot createSnapshot(JobFingerprint job, RequirementIdentity reqId, long profileVersion, String finalPrompt) {
        return createSnapshot(job, reqId, profileVersion, finalPrompt, "ret_v1");
    }

    private AnalysisDependencySnapshot createSnapshot(JobFingerprint job, RequirementIdentity reqId, long profileVersion, String finalPrompt, String retFingerprint) {
        return new AnalysisDependencySnapshot(
                job,
                100L,
                new ProfileArtifactIdentity("u1", profileVersion),
                200L,
                reqId,
                "rule_v1",
                new RetrievalSemanticFingerprint(retFingerprint),
                null,
                new LlmSemanticFingerprint(finalPrompt),
                null
        );
    }

    private AnalysisDependencySnapshot createSnapshot(JobFingerprint job, RequirementIdentity reqId, long profileVersion, LlmSemanticSnapshot llmSnapshot) {
        return new AnalysisDependencySnapshot(
                job,
                100L,
                new ProfileArtifactIdentity("u1", profileVersion),
                200L,
                reqId,
                "rule_v1",
                new RetrievalSemanticFingerprint("ret1"),
                null,
                analysisFactory.finalLlmFingerprint(llmSnapshot),
                llmSnapshot
        );
    }
}
