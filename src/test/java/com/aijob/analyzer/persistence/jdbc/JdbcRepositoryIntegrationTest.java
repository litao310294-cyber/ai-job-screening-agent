package com.aijob.analyzer.persistence.jdbc;

import com.aijob.analyzer.analysis.CanonicalCommitResult;
import com.aijob.analyzer.analysis.domain.*;
import com.aijob.analyzer.analysis.identity.*;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.persistence.model.DbReadResult;
import com.aijob.analyzer.profile.domain.*;
import com.aijob.analyzer.requirement.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class JdbcRepositoryIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("aijob")
            .withUsername("test")
            .withPassword("test");

    private static JdbcTemplate jdbc;
    private static final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    private final JdbcCandidateProfileRepository profileRepo = new JdbcCandidateProfileRepository(jdbc, json);
    private final JdbcRequirementSetRepository requirementRepo = new JdbcRequirementSetRepository(jdbc, json);
    private final JdbcAnalysisResultRepository analysisRepo = new JdbcAnalysisResultRepository(jdbc, json);

    @BeforeAll
    static void setup() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(MYSQL.getDriverClassName());
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @Test
    void candidateProfileLifecycle() {
        String userId = "u1";
        String resume = "fingerprint_resume";
        String parser = "fingerprint_parser";

        // 1. Create Building
        ProfileArtifactRef ref = profileRepo.createBuilding(userId, resume, parser);
        assertNotNull(ref);
        assertEquals(userId, ref.identity().userId());

        // 2. Save Validated
        SourceReference src = new SourceReference("s1", "Resume", "Skills", "Java Specialist");
        CandidateProfile profile = new CandidateProfile(
                ref.artifactId(),
                ref.identity(),
                resume,
                parser,
                ProfileStatus.BUILDING,
                List.of(new StructuredFact("name", "John Doe", src)),
                List.of(new ProfileFact("F0001", ref.artifactId(), "Skill", "Java", src)),
                List.of(new ContextOnlyFact("Extra", "Detail", src))
        );
        profileRepo.saveValidatedProfile(profile);

        // 3. Find
        DbReadResult<CandidateProfile> found = profileRepo.find(ref.identity());
        assertEquals(DbReadOutcome.FOUND, found.outcome());
        assertEquals(1, found.value().retrievalFacts().size());
        assertEquals("Java", found.value().retrievalFacts().get(0).normalizedText());

        // 4. Mark Ready and Publish
        profileRepo.markReadyAndPublishCurrent(ref.identity());
        Optional<ProfileArtifactIdentity> current = profileRepo.findCurrentReady(userId);
        assertTrue(current.isPresent());
        assertEquals(ref.identity(), current.get());
    }

    @Test
    void requirementSetLifecycle() {
        RequirementIdentity id = new RequirementIdentity(
                new JobFingerprint("job1"),
                new ParserDependencyFingerprint("parser1")
        );
        ValidatedRequirementPayload payload = new ValidatedRequirementPayload(
                id,
                List.of(new StructuredConstraint("Constraint", "==", "Value", "Must match Value")),
                List.of(new AtomicRequirement("R0001", "Technical", "Java 17", RequirementPriority.REQUIRED, "Must have Java 17")),
                List.of("Extra context")
        );

        // 1. Insert
        RequirementSet set = requirementRepo.insertCanonical(payload);
        assertNotNull(set);
        assertEquals(id, set.identity());

        // 2. Find
        DbReadResult<RequirementSet> found = requirementRepo.find(id);
        assertEquals(DbReadOutcome.FOUND, found.outcome());
        assertEquals(1, found.value().retrievalRequirements().size());
        assertEquals("Java 17", found.value().retrievalRequirements().get(0).normalizedText());

        // 3. Duplicate Insert (Idempotency)
        RequirementSet reused = requirementRepo.insertCanonical(payload);
        assertEquals(set.artifactId(), reused.artifactId());
    }

    @Test
    void analysisResultLifecycle() {
        AnalysisIdentity id = new AnalysisIdentity(
                new JobFingerprint("job2"),
                new AnalysisDependencyFingerprint("dep2")
        );
        AnalysisDependencySnapshot snapshot = new AnalysisDependencySnapshot(
                new JobFingerprint("job2"),
                100L,
                new ProfileArtifactIdentity("u2", 100L),
                200L,
                new RequirementIdentity(new JobFingerprint("job2"), new ParserDependencyFingerprint("p2")),
                "rule_v1",
                new RetrievalSemanticFingerprint("ret1"),
                null,
                new LlmSemanticFingerprint("llm1"),
                null
        );
        JobAnalysisResult result = new JobAnalysisResult(
                JobDecision.RECOMMEND_APPLY,
                "Overall good",
                List.of(new RequirementAssessment("R0001", RequirementAssessmentStatus.SUPPORTED, List.of("F0001"), "Strong Java", "None")),
                List.of("Risk"),
                List.of("Strength"),
                "Apply",
                "Summary"
        );

        // 1. Commit
        CanonicalCommitResult commit = analysisRepo.commitCanonical(id, result, snapshot, null, null);
        assertTrue(commit instanceof CanonicalCommitResult.Inserted);
        AnalysisArtifact artifact = ((CanonicalCommitResult.Inserted) commit).artifact();
        assertEquals(id, artifact.identity());

        // 2. Read
        DbReadResult<AnalysisArtifact> found = analysisRepo.findCanonical(id);
        assertEquals(DbReadOutcome.FOUND, found.outcome());
        assertEquals(JobDecision.RECOMMEND_APPLY, found.value().result().decision());

        // 3. Duplicate Commit (Idempotency)
        CanonicalCommitResult reused = analysisRepo.commitCanonical(id, result, snapshot, null, null);
        assertTrue(reused instanceof CanonicalCommitResult.Reused);
        assertEquals(artifact.artifactId(), ((CanonicalCommitResult.Reused) reused).artifact().artifactId());
    }
}
