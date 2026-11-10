package com.aijob.analyzer.retrieval.vector;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.retrieval.RetrievalSemanticSnapshot;
import com.aijob.analyzer.retrieval.domain.RankedHit;
import com.aijob.analyzer.retrieval.domain.RetrievalQuery;
import com.aijob.analyzer.retrieval.port.EmbeddingClient;
import com.aijob.analyzer.retrieval.port.EmbeddingResponse;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class ExactCosineRetriever implements VectorRetriever {

    private final VectorProjectionStore store;
    private final EmbeddingClient embeddingClient;
    private final RetrievalSemanticSnapshot snapshot;

    public ExactCosineRetriever(
            VectorProjectionStore store,
            EmbeddingClient embeddingClient,
            RetrievalSemanticSnapshot snapshot) {
        this.store = store;
        this.embeddingClient = embeddingClient;
        this.snapshot = snapshot;
    }

    @Override
    public List<RankedHit> search(ProfileArtifactIdentity profile, RetrievalQuery query, int limit) {
        String modelIdentity = snapshot.embeddingModelIdentity();

        EmbeddingResponse queryEmbedding = embeddingClient.embed(modelIdentity, query.text());
        float[] queryVector = queryEmbedding.vector();

        // Validate query vector
        for (float v : queryVector) {
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                throw new IllegalArgumentException("Non-finite value in query embedding");
            }
        }

        List<VectorRecord> records = store.load(profile);

        return records.stream()
                .map(record -> {
                    float score = CosineSimilarity.calculate(queryVector, record.vector());
                    return new RankedHit(record.chunkId(), record.factId(), score, 0);
                })
                .sorted(Comparator.comparingDouble(RankedHit::rawScore).reversed())
                .limit(limit)
                .map(hit -> new RankedHit(hit.chunkId(), hit.factId(), hit.rawScore(), 0)) // Temporarily 0 rank
                .collect(Collectors.collectingAndThen(Collectors.toList(), list -> {
                    for (int i = 0; i < list.size(); i++) {
                        var old = list.get(i);
                        list.set(i, new RankedHit(old.chunkId(), old.factId(), old.rawScore(), i + 1));
                    }
                    return list;
                }));
    }
}
