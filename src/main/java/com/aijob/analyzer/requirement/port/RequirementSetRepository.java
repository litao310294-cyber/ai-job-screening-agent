package com.aijob.analyzer.requirement.port;

import com.aijob.analyzer.analysis.identity.RequirementIdentity;
import com.aijob.analyzer.persistence.model.DbReadResult;
import com.aijob.analyzer.requirement.domain.RequirementSet;
import com.aijob.analyzer.requirement.domain.ValidatedRequirementPayload;

public interface RequirementSetRepository {
    DbReadResult<RequirementSet> find(RequirementIdentity id);

    RequirementSet insertCanonical(ValidatedRequirementPayload payload);
}
