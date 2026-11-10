package com.aijob.analyzer.retrieval.vector;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryVectorProjectionStore implements VectorProjectionStore {

    private final Map<ProfileArtifactIdentity, List<VectorRecord>> store = new ConcurrentHashMap<>();

    @Override
    public void store(ProfileArtifactIdentity identity, List<VectorRecord> records) {
        store.put(identity, records);
    }

    @Override
    public List<VectorRecord> load(ProfileArtifactIdentity identity) {
        return store.getOrDefault(identity, List.of());
    }
}
