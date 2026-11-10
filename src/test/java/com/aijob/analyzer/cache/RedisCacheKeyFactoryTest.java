package com.aijob.analyzer.cache;

import com.aijob.analyzer.analysis.identity.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisCacheKeyFactoryTest {
    private final RedisCacheKeyFactory keys = new RedisCacheKeyFactory();
    @Test void usesFrozenBusinessKeysOnly() {
        assertEquals("profile:v1:user:7", keys.profile(new ProfileArtifactIdentity("user", 7)));
        assertEquals("requirement:v1:job:parser", keys.requirement(new RequirementIdentity(new JobFingerprint("job"), new ParserDependencyFingerprint("parser"))));
        assertEquals("analysis:v1:job:analysis", keys.analysis(new AnalysisIdentity(new JobFingerprint("job"), new AnalysisDependencyFingerprint("analysis"))));
    }
}
