package com.aijob.analyzer.retrieval.keyword;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.profile.domain.ProfileFact;
import com.aijob.analyzer.profile.domain.ProfileStatus;
import com.aijob.analyzer.profile.domain.SourceReference;
import com.aijob.analyzer.retrieval.DefaultProfileChunker;
import com.aijob.analyzer.retrieval.domain.RankedHit;
import com.aijob.analyzer.retrieval.domain.RetrievalQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LuceneKeywordRetrieverTest {

    private LuceneKeywordRetriever retriever;
    private LuceneProfileIndex indexer;
    private ProfileArtifactIdentity profileIdentity;

    @BeforeEach
    void setUp() {
        // In a real scenario, this would be a shared directory or a file-based one.
        // For testing, we use an in-memory directory provided by LuceneDirectoryProvider.
        LuceneDirectoryProvider directoryProvider = new InMemoryLuceneDirectoryProvider();
        indexer = new LuceneProfileIndex(directoryProvider);
        retriever = new LuceneKeywordRetriever(directoryProvider);

        profileIdentity = new ProfileArtifactIdentity("U001", 1);
    }

    @Test
    void should_return_matching_chunk_when_exact_term_matches() {
        // Given
        String factText = "使用 Redis + Lua 原子完成库存判断和扣减";
        ProfileFact fact = new ProfileFact(
                "F001",
                100L,
                "SKILL",
                factText,
                new SourceReference("S1", "Resume", "Exp", factText)
        );
        CandidateProfile profile = new CandidateProfile(
                100L,
                profileIdentity,
                "hash1",
                "dep1",
                ProfileStatus.BUILDING,
                List.of(),
                List.of(fact),
                List.of()
        );

        var chunks = new DefaultProfileChunker().chunk(profile);
        indexer.index(profileIdentity, chunks);

        // When
        RetrievalQuery query = new RetrievalQuery("R1", "Redis Lua");
        List<RankedHit> hits = retriever.search(profileIdentity, query, 20);

        // Then
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).factId()).isEqualTo("F001");
        assertThat(hits.get(0).rank()).isEqualTo(1);
        assertThat(hits.get(0).chunkId()).startsWith("F001-C");
    }

    @Test
    void should_not_leak_chunks_from_other_profiles() {
        // Given profile 1
        ProfileFact fact1 = new ProfileFact("F001", 100L, "SKILL", "Java Spring", new SourceReference("S1", "R", "E", "Java Spring"));
        CandidateProfile p1 = new CandidateProfile(100L, profileIdentity, "h1", "d1", ProfileStatus.BUILDING, List.of(), List.of(fact1), List.of());
        indexer.index(profileIdentity, new DefaultProfileChunker().chunk(p1));

        // Given profile 2
        ProfileArtifactIdentity otherIdentity = new ProfileArtifactIdentity("U002", 1);
        ProfileFact fact2 = new ProfileFact("F002", 101L, "SKILL", "Java Spring", new SourceReference("S2", "R", "E", "Java Spring"));
        CandidateProfile p2 = new CandidateProfile(101L, otherIdentity, "h2", "d2", ProfileStatus.BUILDING, List.of(), List.of(fact2), List.of());
        indexer.index(otherIdentity, new DefaultProfileChunker().chunk(p2));

        // When searching for profile 1
        RetrievalQuery query = new RetrievalQuery("R1", "Java");
        List<RankedHit> hits = retriever.search(profileIdentity, query, 20);

        // Then only profile 1 hits should be returned
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).factId()).isEqualTo("F001");
    }
}
