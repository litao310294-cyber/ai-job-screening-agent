package com.aijob.analyzer.requirement.repository;

import com.aijob.analyzer.analysis.identity.RequirementIdentity;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.persistence.model.DbReadResult;
import com.aijob.analyzer.requirement.domain.RequirementSet;
import com.aijob.analyzer.requirement.domain.ValidatedRequirementPayload;
import com.aijob.analyzer.requirement.port.RequirementSetRepository;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("test")
public class InMemoryRequirementSetRepository implements RequirementSetRepository {

    private final Map<RequirementIdentity, RequirementSet> requirementSets = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(2000);

    @Override
    public DbReadResult<RequirementSet> find(RequirementIdentity id) {
        RequirementSet set = requirementSets.get(id);
        if (set == null) {
            return new DbReadResult<>(DbReadOutcome.NOT_FOUND, null, null);
        }
        return new DbReadResult<>(DbReadOutcome.FOUND, set, null);
    }

    @Override
    public RequirementSet insertCanonical(ValidatedRequirementPayload payload) {
        return requirementSets.computeIfAbsent(payload.identity(), id ->
            new RequirementSet(
                idSequence.incrementAndGet(),
                id,
                payload.structuredConstraints(),
                payload.retrievalRequirements(),
                payload.ignoredContext()
            )
        );
    }
}
