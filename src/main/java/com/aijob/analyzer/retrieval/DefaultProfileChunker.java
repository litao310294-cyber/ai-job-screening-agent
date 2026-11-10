package com.aijob.analyzer.retrieval;

import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.profile.domain.ProfileFact;
import com.aijob.analyzer.retrieval.domain.ProfileChunk;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DefaultProfileChunker implements ProfileChunker {

    @Override
    public List<ProfileChunk> chunk(CandidateProfile profile) {
        return profile.retrievalFacts().stream()
                .map(fact -> createChunk(profile, fact))
                .collect(Collectors.toList());
    }

    private ProfileChunk createChunk(CandidateProfile profile, ProfileFact fact) {
        String chunkId = fact.factId() + "-C001";
        String content = fact.factType() + " | " + fact.normalizedText();

        return new ProfileChunk(
                chunkId,
                profile.identity(),
                fact.factId(),
                fact.factType(),
                content,
                fact.source(),
                Map.of()
        );
    }
}
