package com.aijob.analyzer.profile.repository;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.persistence.model.DbReadResult;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.profile.domain.ProfileArtifactRef;
import com.aijob.analyzer.profile.domain.ProfilePublishOutcome;
import com.aijob.analyzer.profile.port.CandidateProfileRepository;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("test")
public class InMemoryCandidateProfileRepository implements CandidateProfileRepository {

    private final Map<ProfileArtifactIdentity, CandidateProfile> profiles = new ConcurrentHashMap<>();
    private final Map<String, ProfileArtifactIdentity> currentVersions = new ConcurrentHashMap<>();
    private final AtomicLong versionSequence = new AtomicLong(1000);

    @Override
    public ProfileArtifactRef createBuilding(String userId, String resumeFingerprint, String parserDependencyFingerprint) {
        long version = versionSequence.incrementAndGet();
        ProfileArtifactIdentity identity = new ProfileArtifactIdentity(userId, version);
        return new ProfileArtifactRef(version, identity);
    }

    @Override
    public void saveValidatedProfile(CandidateProfile profile) {
        profiles.put(profile.identity(), profile);
    }

    @Override
    public DbReadResult<CandidateProfile> find(ProfileArtifactIdentity id) {
        CandidateProfile profile = profiles.get(id);
        if (profile == null) {
            return new DbReadResult<>(DbReadOutcome.NOT_FOUND, null, null);
        }
        return new DbReadResult<>(DbReadOutcome.FOUND, profile, null);
    }

    @Override
    public Optional<ProfileArtifactIdentity> findCurrentReady(String userId) {
        return Optional.ofNullable(currentVersions.get(userId));
    }

    @Override
    public ProfilePublishOutcome markReadyAndPublishCurrent(ProfileArtifactIdentity id) {
        // Rule RT-12: Pointer must not move backward
        currentVersions.compute(id.userId(), (userId, current) -> {
            if (current == null || id.profileVersion() > current.profileVersion()) {
                return id;
            }
            return current;
        });

        ProfileArtifactIdentity actualCurrent = currentVersions.get(id.userId());
        if (actualCurrent.equals(id)) {
            return ProfilePublishOutcome.PUBLISHED_CURRENT;
        } else {
            return ProfilePublishOutcome.READY_NOT_CURRENT;
        }
    }

    @Override
    public void markFailed(ProfileArtifactIdentity id, String reason) {
        // In a real DB, we would update status to FAILED
    }
}
