package com.aijob.analyzer.retrieval;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.retrieval.domain.ProfileChunk;
import com.aijob.analyzer.retrieval.domain.ProjectionBuildResult;
import com.aijob.analyzer.retrieval.keyword.LuceneProfileIndex;
import com.aijob.analyzer.retrieval.vector.ProfileVectorIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ProfileProjectionService {

    private static final Logger log = LoggerFactory.getLogger(ProfileProjectionService.class);

    private final ProfileChunker chunker;
    private final LuceneProfileIndex keywordIndexer;
    private final ProfileVectorIndexer vectorIndexer;

    public ProfileProjectionService(
            ProfileChunker chunker,
            LuceneProfileIndex keywordIndexer,
            ProfileVectorIndexer vectorIndexer) {
        this.chunker = chunker;
        this.keywordIndexer = keywordIndexer;
        this.vectorIndexer = vectorIndexer;
    }

    public ProjectionBuildResult buildProjections(CandidateProfile profile) {
        ProfileArtifactIdentity identity = profile.identity();
        try {
            log.info("Building projections for profile: {}", identity);
            List<ProfileChunk> chunks = chunker.chunk(profile);

            // Build Keyword (Lucene) projection
            keywordIndexer.index(identity, chunks);

            // Build Vector projection
            vectorIndexer.index(identity, chunks);

            log.info("Successfully built projections for profile: {}", identity);
            return ProjectionBuildResult.SUCCESS;
        } catch (Exception e) {
            log.error("Failed to build projections for profile: {}", identity, e);
            return ProjectionBuildResult.FAILURE;
        }
    }
}
