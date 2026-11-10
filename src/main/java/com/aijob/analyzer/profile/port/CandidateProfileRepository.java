package com.aijob.analyzer.profile.port;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.persistence.model.DbReadResult;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.profile.domain.ProfileArtifactRef;
import com.aijob.analyzer.profile.domain.ProfilePublishOutcome;

import java.util.Optional;

public interface CandidateProfileRepository {
    ProfileArtifactRef createBuilding(
            String userId,
            String resumeFingerprint,
            String parserDependencyFingerprint);

    void saveValidatedProfile(CandidateProfile profile);

    DbReadResult<CandidateProfile> find(ProfileArtifactIdentity id);

    Optional<ProfileArtifactIdentity> findCurrentReady(String userId);

    ProfilePublishOutcome markReadyAndPublishCurrent(ProfileArtifactIdentity id);

    void markFailed(ProfileArtifactIdentity id, String reason);
}
