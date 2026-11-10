package com.aijob.analyzer.retrieval.vector;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;

import java.util.List;

public interface VectorProjectionStore {
    void store(ProfileArtifactIdentity identity, List<VectorRecord> records);
    List<VectorRecord> load(ProfileArtifactIdentity identity);
}
