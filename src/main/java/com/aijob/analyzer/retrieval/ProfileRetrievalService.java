package com.aijob.analyzer.retrieval;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.profile.port.CandidateProfileRepository;
import com.aijob.analyzer.requirement.domain.AtomicRequirement;
import com.aijob.analyzer.requirement.domain.RequirementSet;
import com.aijob.analyzer.retrieval.domain.*;
import com.aijob.analyzer.retrieval.fusion.RankFusion;
import com.aijob.analyzer.retrieval.vector.VectorRetriever;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceRecorder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ProfileRetrievalService {

    private final KeywordRetriever keywordRetriever;
    private final VectorRetriever vectorRetriever;
    private final RankFusion rankFusion;
    private final EvidenceSelector evidenceSelector;
    private final RetrievalQueryBuilder queryBuilder;
    private final CandidateProfileRepository profileRepository;
    private final ProfileChunker chunker;
    private final RetrievalSemanticSnapshot snapshot;

    public ProfileRetrievalService(
            KeywordRetriever keywordRetriever,
            VectorRetriever vectorRetriever,
            RankFusion rankFusion,
            EvidenceSelector evidenceSelector,
            RetrievalQueryBuilder queryBuilder,
            CandidateProfileRepository profileRepository,
            ProfileChunker chunker,
            RetrievalSemanticSnapshot snapshot) {
        this.keywordRetriever = keywordRetriever;
        this.vectorRetriever = vectorRetriever;
        this.rankFusion = rankFusion;
        this.evidenceSelector = evidenceSelector;
        this.queryBuilder = queryBuilder;
        this.profileRepository = profileRepository;
        this.chunker = chunker;
        this.snapshot = snapshot;
    }

    public RetrievalResult retrieve(
            ProfileArtifactIdentity identity,
            RequirementSet requirements,
            TraceContext trace,
            TraceRecorder recorder) {

        var profileRead = profileRepository.find(identity);
        if (profileRead.outcome() != com.aijob.analyzer.persistence.model.DbReadOutcome.FOUND) {
            throw new RuntimeException("Profile not found: " + identity);
        }
        CandidateProfile profile = profileRead.value();
        List<ProfileChunk> chunks = chunker.chunk(profile);
        Map<String, ProfileChunk> chunkById = chunks.stream()
                .collect(Collectors.toMap(ProfileChunk::chunkId, c -> c));

        Map<String, List<Evidence>> evidenceByRequirement = new HashMap<>();

        int totalKeywordHits = 0;
        int totalVectorHits = 0;
        int totalEvidenceCount = 0;

        for (AtomicRequirement req : requirements.retrievalRequirements()) {
            RetrievalQuery query = queryBuilder.from(req);

            List<RankedHit> keywordHits = keywordRetriever.search(identity, query, snapshot.keywordCandidateLimit());
            List<RankedHit> vectorHits = vectorRetriever.search(identity, query, snapshot.vectorCandidateLimit());

            totalKeywordHits += keywordHits.size();
            totalVectorHits += vectorHits.size();

            List<FusedHit> fusedHits = rankFusion.fuse(keywordHits, vectorHits);

            List<Evidence> evidence = evidenceSelector.select(req, fusedHits, chunkById, snapshot.finalEvidenceTopK());
            totalEvidenceCount += evidence.size();

            evidenceByRequirement.put(req.requirementId(), evidence);
        }

        if (recorder != null && trace != null) {
            recorder.event(trace, com.aijob.analyzer.trace.TraceEventType.TRANSITION, Map.of(
                    "stage", "RETRIEVAL",
                    "requirementCount", requirements.retrievalRequirements().size(),
                    "totalKeywordHits", totalKeywordHits,
                    "totalVectorHits", totalVectorHits,
                    "totalEvidenceCount", totalEvidenceCount,
                    "profileVersion", identity.profileVersion()
            ));
        }

        return new RetrievalResult(evidenceByRequirement);
    }

    public RetrievalResult retrieve(ProfileArtifactIdentity identity, RequirementSet requirements) {
        return retrieve(identity, requirements, null, null);
    }
}
