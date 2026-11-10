package com.aijob.analyzer.profile;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.persistence.model.DbReadResult;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.profile.port.CandidateProfileRepository;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

@Component
public class CurrentProfileResolver {
    private final CandidateProfileRepository repository;

    public CurrentProfileResolver(CandidateProfileRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public Optional<CandidateProfile> resolveCurrent(String userId) {
        return repository.findCurrentReady(userId)
                .flatMap(identity -> {
                    DbReadResult<CandidateProfile> result = repository.find(identity);
                    if (result.outcome() == DbReadOutcome.FOUND) {
                        return Optional.of(result.value());
                    }
                    return Optional.empty();
                });
    }
}
