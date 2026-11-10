package com.aijob.analyzer.llm;

import com.aijob.analyzer.analysis.identity.LlmSemanticFingerprint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:isolated;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false"})
@ActiveProfiles("test")
class LlmSemanticVersionsTest {

    @Autowired(required = false)
    private LlmSemanticVersions versions;

    @Autowired(required = false)
    private LlmSemanticSnapshotFactory snapshotFactory;

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
    void should_resolve_versions_from_properties() {
        assertThat(versions).isNotNull();
        LlmSemanticSnapshot resumeParser = versions.getResumeParserSnapshot();
        assertThat(resumeParser.promptVersion()).isEqualTo("resume-v1");
        assertThat(resumeParser.modelIdentity()).isEqualTo("gpt-4o");
        assertThat(resumeParser.generationConfig().temperature()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void identical_snapshots_should_have_same_fingerprint() {
        assertThat(snapshotFactory).isNotNull();
        LlmSemanticSnapshot s1 = versions.getFinalAnalysisSnapshot();
        LlmSemanticSnapshot s2 = versions.getFinalAnalysisSnapshot();

        assertThat(snapshotFactory.createFingerprint(s1))
                .isEqualTo(snapshotFactory.createFingerprint(s2));
    }

    @Test
    void different_generation_config_should_derive_different_fingerprints() {
        assertThat(snapshotFactory).isNotNull();
        LlmSemanticSnapshot s1 = versions.getFinalAnalysisSnapshot();
        LlmSemanticSnapshot s2 = new LlmSemanticSnapshot(
                s1.promptVersion(),
                s1.modelIdentity(),
                s1.schemaVersion(),
                new LlmGenerationConfigSnapshot(
                        s1.generationConfig().temperature().add(BigDecimal.valueOf(0.1)),
                        s1.generationConfig().topP(),
                        s1.generationConfig().maxOutputTokens(),
                        s1.generationConfig().responseFormat()
                )
        );

        LlmSemanticFingerprint f1 = snapshotFactory.createFingerprint(s1);
        LlmSemanticFingerprint f2 = snapshotFactory.createFingerprint(s2);

        assertThat(f1).isNotEqualTo(f2);
    }

    @Test
    void revised_prompt_and_schema_invalidate_previous_parser_and_analysis_identities() {
        var current = versions.getFinalAnalysisSnapshot();
        var previous = new LlmSemanticSnapshot("final-analysis-v1.1", current.modelIdentity(), "job-analysis-v1", current.generationConfig());
        assertThat(snapshotFactory.createFingerprint(current)).isNotEqualTo(snapshotFactory.createFingerprint(previous));
        var parser = versions.getRequirementParserSnapshot();
        var oldParser = new LlmSemanticSnapshot("requirement-parser-v1", parser.modelIdentity(), "requirement-set-v1", parser.generationConfig());
        var identities = new com.aijob.analyzer.analysis.identity.RequirementIdentityFactory();
        var job = new com.aijob.analyzer.analysis.identity.JobFingerprint("TEST");
        assertThat(identities.create(job, parser)).isNotEqualTo(identities.create(job, oldParser));
    }
}
