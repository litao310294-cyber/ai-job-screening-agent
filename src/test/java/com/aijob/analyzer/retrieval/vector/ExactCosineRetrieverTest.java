package com.aijob.analyzer.retrieval.vector;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.retrieval.RetrievalSemanticSnapshot;
import com.aijob.analyzer.retrieval.domain.RankedHit;
import com.aijob.analyzer.retrieval.domain.RetrievalQuery;
import com.aijob.analyzer.retrieval.port.EmbeddingClient;
import com.aijob.analyzer.retrieval.port.EmbeddingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExactCosineRetrieverTest {

    private ExactCosineRetriever retriever;
    private VectorProjectionStore store;
    private EmbeddingClient embeddingClient;
    private ProfileArtifactIdentity profileIdentity;
    private RetrievalSemanticSnapshot snapshot;

    @BeforeEach
    void setUp() {
        store = new InMemoryVectorProjectionStore();
        embeddingClient = mock(EmbeddingClient.class);
        snapshot = new RetrievalSemanticSnapshot(
                "v1", "lucene", "v1", "model1", 20, 20, 60, 5
        );
        retriever = new ExactCosineRetriever(store, embeddingClient, snapshot);
        profileIdentity = new ProfileArtifactIdentity("U001", 1);
    }

    @Test
    void should_throw_exception_on_dimension_mismatch() {
        // Given
        VectorRecord record1 = new VectorRecord("C1", "F1", profileIdentity, "model1", "v1", 2, new float[]{1.0f, 0.0f});
        store.store(profileIdentity, List.of(record1));

        when(embeddingClient.embed(anyString(), eq("query")))
                .thenReturn(new EmbeddingResponse(new float[]{1.0f, 0.0f, 0.0f}, "model1", "v1", 5));

        // When/Then
        RetrievalQuery query = new RetrievalQuery("R1", "query");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
            retriever.search(profileIdentity, query, 10)
        );
    }

    @Test
    void should_throw_exception_on_nan_in_query() {
        // Given
        VectorRecord record1 = new VectorRecord("C1", "F1", profileIdentity, "model1", "v1", 2, new float[]{1.0f, 0.0f});
        store.store(profileIdentity, List.of(record1));

        when(embeddingClient.embed(anyString(), eq("query")))
                .thenReturn(new EmbeddingResponse(new float[]{Float.NaN, 0.0f}, "model1", "v1", 5));

        // When/Then
        RetrievalQuery query = new RetrievalQuery("R1", "query");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
            retriever.search(profileIdentity, query, 10)
        );
    }

    @Test
    void should_throw_exception_on_infinity_in_query() {
        // Given
        VectorRecord record1 = new VectorRecord("C1", "F1", profileIdentity, "model1", "v1", 2, new float[]{1.0f, 0.0f});
        store.store(profileIdentity, List.of(record1));

        when(embeddingClient.embed(anyString(), eq("query")))
                .thenReturn(new EmbeddingResponse(new float[]{Float.POSITIVE_INFINITY, 0.0f}, "model1", "v1", 5));

        // When/Then
        RetrievalQuery query = new RetrievalQuery("R1", "query");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
            retriever.search(profileIdentity, query, 10)
        );
    }

    @Test
    void should_calculate_correct_cosine_similarity() {
        float[] v1 = {1.0f, 0.0f};
        float[] v2 = {1.0f, 0.0f};
        float[] v3 = {0.0f, 1.0f};

        assertThat(CosineSimilarity.calculate(v1, v2)).isCloseTo(1.0f, within(0.0001f));
        assertThat(CosineSimilarity.calculate(v1, v3)).isCloseTo(0.0f, within(0.0001f));
    }

    @Test
    void should_rank_hits_by_cosine_similarity() {
        // Given
        VectorRecord record1 = new VectorRecord("C1", "F1", profileIdentity, "model1", "v1", 2, new float[]{1.0f, 0.0f});
        VectorRecord record2 = new VectorRecord("C2", "F2", profileIdentity, "model1", "v1", 2, new float[]{0.7f, 0.7f});
        store.store(profileIdentity, List.of(record1, record2));

        when(embeddingClient.embed(anyString(), eq("query")))
                .thenReturn(new EmbeddingResponse(new float[]{1.0f, 0.1f}, "model1", "v1", 5));

        // When
        RetrievalQuery query = new RetrievalQuery("R1", "query");
        List<RankedHit> hits = retriever.search(profileIdentity, query, 10);

        // Then
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).chunkId()).isEqualTo("C1");
        assertThat(hits.get(0).rank()).isEqualTo(1);
        assertThat(hits.get(1).chunkId()).isEqualTo("C2");
        assertThat(hits.get(1).rank()).isEqualTo(2);
    }

    @Test
    void should_not_leak_vectors_from_other_profiles() {
        // Given
        VectorRecord record1 = new VectorRecord("C1", "F1", profileIdentity, "model1", "v1", 2, new float[]{1.0f, 0.0f});
        store.store(profileIdentity, List.of(record1));

        ProfileArtifactIdentity otherIdentity = new ProfileArtifactIdentity("U002", 1);
        VectorRecord record2 = new VectorRecord("C2", "F2", otherIdentity, "model1", "v1", 2, new float[]{1.0f, 0.0f});
        store.store(otherIdentity, List.of(record2));

        when(embeddingClient.embed(anyString(), eq("query")))
                .thenReturn(new EmbeddingResponse(new float[]{1.0f, 0.0f}, "model1", "v1", 5));

        // When
        RetrievalQuery query = new RetrievalQuery("R1", "query");
        List<RankedHit> hits = retriever.search(profileIdentity, query, 10);

        // Then
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).chunkId()).isEqualTo("C1");
    }
}
