package com.aijob.analyzer.retrieval;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.profile.domain.ProfileStatus;
import com.aijob.analyzer.retrieval.domain.ProjectionBuildResult;
import com.aijob.analyzer.retrieval.keyword.LuceneProfileIndex;
import com.aijob.analyzer.retrieval.vector.ProfileVectorIndexer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProfileProjectionServiceTest {

    private ProfileProjectionService service;
    private ProfileChunker chunker;
    private LuceneProfileIndex keywordIndexer;
    private ProfileVectorIndexer vectorIndexer;
    private CandidateProfile profile;

    @BeforeEach
    void setUp() {
        chunker = mock(ProfileChunker.class);
        keywordIndexer = mock(LuceneProfileIndex.class);
        vectorIndexer = mock(ProfileVectorIndexer.class);
        service = new ProfileProjectionService(chunker, keywordIndexer, vectorIndexer);

        profile = new CandidateProfile(
                100L, new ProfileArtifactIdentity("U1", 1), "h1", "d1", ProfileStatus.BUILDING,
                List.of(), List.of(), List.of()
        );
    }

    @Test
    void should_return_success_when_all_projections_succeed() {
        // When
        ProjectionBuildResult result = service.buildProjections(profile);

        // Then
        assertThat(result).isEqualTo(ProjectionBuildResult.SUCCESS);
        verify(keywordIndexer).index(any(), any());
        verify(vectorIndexer).index(any(), any());
    }

    @Test
    void should_return_failure_when_keyword_indexing_fails() {
        // Given
        doThrow(new RuntimeException("Lucene error")).when(keywordIndexer).index(any(), any());

        // When
        ProjectionBuildResult result = service.buildProjections(profile);

        // Then
        assertThat(result).isEqualTo(ProjectionBuildResult.FAILURE);
    }

    @Test
    void should_return_failure_when_vector_indexing_fails() {
        // Given
        doThrow(new RuntimeException("Vector error")).when(vectorIndexer).index(any(), any());

        // When
        ProjectionBuildResult result = service.buildProjections(profile);

        // Then
        assertThat(result).isEqualTo(ProjectionBuildResult.FAILURE);
    }
}
