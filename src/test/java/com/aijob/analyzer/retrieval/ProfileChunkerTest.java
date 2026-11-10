package com.aijob.analyzer.retrieval;

import com.aijob.analyzer.profile.domain.*;
import com.aijob.analyzer.support.FixtureFactory;
import com.aijob.analyzer.retrieval.domain.ProfileChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProfileChunkerTest {

    private final ProfileChunker chunker = new DefaultProfileChunker();

    @Test
    void oneRetrievalFactGeneratesOneChunk() {
        var profile = FixtureFactory.candidateProfile(1L, "user-1");
        var fact = new ProfileFact(
                "fact-1",
                1L,
                "WORK_EXPERIENCE",
                "Worked as Java Developer",
                new SourceReference("RESUME-1", "Resume", "Experience", "Worked as Java Developer")
        );

        var profileWithFact = new CandidateProfile(
                profile.artifactId(),
                profile.identity(),
                profile.resumeFingerprint(),
                profile.parserDependencyFingerprint(),
                profile.status(),
                List.of(),
                List.of(fact),
                List.of()
        );

        List<ProfileChunk> chunks = chunker.chunk(profileWithFact);

        assertEquals(1, chunks.size());
        ProfileChunk chunk = chunks.getFirst();
        assertEquals("fact-1-C001", chunk.chunkId());
        assertEquals("fact-1", chunk.factId());
        assertEquals(profile.identity(), chunk.profileIdentity());
        assertEquals("WORK_EXPERIENCE", chunk.sourceType());
        assertEquals("WORK_EXPERIENCE | Worked as Java Developer", chunk.content());
        assertEquals(fact.source(), chunk.source());
    }

    @Test
    void structuredFactsAndContextOnlyAreExcluded() {
        var profile = FixtureFactory.candidateProfile(1L, "user-1");
        var structuredFact = new StructuredFact("TYPE", "VAL", null);
        var contextOnlyFact = new ContextOnlyFact("TYPE", "VAL", null);

        var profileWithMixedFacts = new CandidateProfile(
                profile.artifactId(),
                profile.identity(),
                profile.resumeFingerprint(),
                profile.parserDependencyFingerprint(),
                profile.status(),
                new java.util.ArrayList<>(List.of(structuredFact)),
                List.of(),
                new java.util.ArrayList<>(List.of(contextOnlyFact))
        );

        List<ProfileChunk> chunks = chunker.chunk(profileWithMixedFacts);

        assertTrue(chunks.isEmpty());
    }

    @Test
    void deterministicOutputForSameInput() {
        var profile = FixtureFactory.candidateProfile(1L, "user-1");
        var fact = new ProfileFact(
                "fact-1",
                1L,
                "SKILL",
                "Java",
                new SourceReference("RESUME-1", "Resume", "Skills", "Java")
        );

        var profileWithFact = new CandidateProfile(
                profile.artifactId(),
                profile.identity(),
                profile.resumeFingerprint(),
                profile.parserDependencyFingerprint(),
                profile.status(),
                List.of(),
                List.of(fact),
                List.of()
        );

        var chunks1 = chunker.chunk(profileWithFact);
        var chunks2 = chunker.chunk(profileWithFact);

        assertEquals(chunks1, chunks2);
    }
}
