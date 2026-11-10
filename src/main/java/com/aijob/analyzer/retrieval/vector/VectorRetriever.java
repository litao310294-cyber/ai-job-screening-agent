package com.aijob.analyzer.retrieval.vector;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.retrieval.domain.RankedHit;
import com.aijob.analyzer.retrieval.domain.RetrievalQuery;

import java.util.List;

public interface VectorRetriever {
    List<RankedHit> search(
            ProfileArtifactIdentity profile,
            RetrievalQuery query,
            int limit);
}
