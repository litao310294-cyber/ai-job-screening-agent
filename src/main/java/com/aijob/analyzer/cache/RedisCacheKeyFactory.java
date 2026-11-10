package com.aijob.analyzer.cache;

import com.aijob.analyzer.analysis.identity.AnalysisIdentity;
import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.analysis.identity.RequirementIdentity;

public final class RedisCacheKeyFactory {
    public String profile(ProfileArtifactIdentity identity) {
        return "profile:v1:" + identity.userId() + ":" + identity.profileVersion();
    }
    public String requirement(RequirementIdentity identity) {
        return "requirement:v1:" + identity.jobFingerprint().value() + ":" + identity.parserDependencyFingerprint().value();
    }
    public String analysis(AnalysisIdentity identity) {
        return "analysis:v1:" + identity.jobFingerprint().value() + ":" + identity.analysisDependencyFingerprint().value();
    }
}
