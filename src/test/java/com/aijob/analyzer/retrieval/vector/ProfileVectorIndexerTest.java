package com.aijob.analyzer.retrieval.vector;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.profile.domain.SourceReference;
import com.aijob.analyzer.retrieval.RetrievalSemanticSnapshot;
import com.aijob.analyzer.retrieval.domain.ProfileChunk;
import com.aijob.analyzer.retrieval.port.EmbeddingClient;
import com.aijob.analyzer.retrieval.port.EmbeddingResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ProfileVectorIndexerTest {

    @Test
    void usesConfiguredSnapshotModelForProfileEmbedding() {
        EmbeddingClient client = mock(EmbeddingClient.class);
        when(client.embed(eq("text-embedding-v4"), anyString()))
                .thenReturn(new EmbeddingResponse(new float[]{1f, 0f}, "text-embedding-v4", "text-embedding-v4", 1));
        VectorProjectionStore store = mock(VectorProjectionStore.class);
        RetrievalSemanticSnapshot snapshot = new RetrievalSemanticSnapshot("q", "a", "v", "text-embedding-v4", 20, 20, 60, 5);

        new ProfileVectorIndexer(client, store, snapshot).index(identity(), List.of(chunk()));

        verify(client).embed("text-embedding-v4", "SKILL | Java backend");
        verify(store).store(eq(identity()), anyList());
        verify(client, never()).embed(eq("default-model"), anyString());
    }

    @Test
    void missingSnapshotModelFailsWithoutDefaultFallback() {
        EmbeddingClient client = mock(EmbeddingClient.class);
        RetrievalSemanticSnapshot snapshot = new RetrievalSemanticSnapshot("q", "a", "v", "", 20, 20, 60, 5);

        assertThatThrownBy(() -> new ProfileVectorIndexer(client, mock(VectorProjectionStore.class), snapshot)
                .index(identity(), List.of(chunk())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_EMBEDDING_MODEL");
        verifyNoInteractions(client);
    }

    private static ProfileArtifactIdentity identity() {
        return new ProfileArtifactIdentity("eval-user", 1L);
    }

    private static ProfileChunk chunk() {
        return new ProfileChunk("chunk-1", identity(), "F0001", "PROFILE", "SKILL | Java backend",
                new SourceReference("s", "title", "skills", "Java backend"), Map.of());
    }
}
