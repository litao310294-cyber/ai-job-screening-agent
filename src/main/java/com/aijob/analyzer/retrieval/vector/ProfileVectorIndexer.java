package com.aijob.analyzer.retrieval.vector;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.retrieval.domain.ProfileChunk;
import com.aijob.analyzer.retrieval.port.EmbeddingClient;
import com.aijob.analyzer.retrieval.port.EmbeddingResponse;
import com.aijob.analyzer.retrieval.RetrievalSemanticSnapshot;

import java.util.ArrayList;
import java.util.List;

public class ProfileVectorIndexer {

    private final EmbeddingClient embeddingClient;
    private final VectorProjectionStore store;
    private final RetrievalSemanticSnapshot snapshot;

    public ProfileVectorIndexer(EmbeddingClient embeddingClient, VectorProjectionStore store,
                                RetrievalSemanticSnapshot snapshot) {
        this.embeddingClient = embeddingClient;
        this.store = store;
        this.snapshot = snapshot;
    }

    public void index(ProfileArtifactIdentity identity, List<ProfileChunk> chunks) {
        String modelIdentity = snapshot.embeddingModelIdentity();
        if (modelIdentity == null || modelIdentity.isBlank()) {
            throw new IllegalStateException("Embedding model is not configured; set AI_EMBEDDING_MODEL");
        }
        List<VectorRecord> records = new ArrayList<>();
        Integer dimension = null;

        for (ProfileChunk chunk : chunks) {
            EmbeddingResponse response = embeddingClient.embed(modelIdentity, chunk.content());

            if (response.vector() == null || response.vector().length == 0) {
                throw new IllegalArgumentException("Empty vector for chunk: " + chunk.chunkId());
            }

            if (dimension == null) {
                dimension = response.vector().length;
            } else if (dimension != response.vector().length) {
                throw new IllegalArgumentException("Inconsistent vector dimensions: expected " + dimension + " but got " + response.vector().length);
            }

            for (float v : response.vector()) {
                if (Float.isNaN(v) || Float.isInfinite(v)) {
                    throw new IllegalArgumentException("Non-finite value in vector for chunk: " + chunk.chunkId());
                }
            }

            records.add(new VectorRecord(
                    chunk.chunkId(),
                    chunk.factId(),
                    identity,
                    modelIdentity,
                    response.providerExposedModelVersion(),
                    dimension,
                    response.vector()
            ));
        }

        store.store(identity, records);
    }
}
