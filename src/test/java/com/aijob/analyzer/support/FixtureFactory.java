package com.aijob.analyzer.support;

import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.analysis.domain.JobDecision;
import com.aijob.analyzer.analysis.domain.RequirementAssessment;
import com.aijob.analyzer.analysis.domain.RequirementAssessmentStatus;
import com.aijob.analyzer.analysis.identity.AnalysisDependencyFingerprint;
import com.aijob.analyzer.analysis.identity.AnalysisDependencySnapshot;
import com.aijob.analyzer.analysis.identity.AnalysisIdentity;
import com.aijob.analyzer.analysis.identity.JobFingerprint;
import com.aijob.analyzer.analysis.identity.ParserDependencyFingerprint;
import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.analysis.identity.RequirementIdentity;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.profile.domain.ProfileStatus;
import com.aijob.analyzer.requirement.domain.RequirementPriority;
import com.aijob.analyzer.requirement.domain.RequirementSet;

import java.time.Instant;
import java.util.List;

public class FixtureFactory {

    public static JobFingerprint jobFingerprint() {
        return new JobFingerprint("f86f7e437faa5a7fce15d1ddcb9eaeaea377667b878e939359a1c13c904a8211");
    }

    public static AnalysisDependencyFingerprint analysisDependencyFingerprint() {
        return new AnalysisDependencyFingerprint("d86f7e437faa5a7fce15d1ddcb9eaeaea377667b878e939359a1c13c904a8211");
    }

    public static AnalysisIdentity analysisIdentity() {
        return new AnalysisIdentity(jobFingerprint(), analysisDependencyFingerprint());
    }

    public static ProfileArtifactIdentity profileIdentity(String userId, int version) {
        return new ProfileArtifactIdentity(userId, version);
    }

    public static CandidateProfile candidateProfile(long artifactId, String userId) {
        return new CandidateProfile(
                artifactId,
                profileIdentity(userId, 1),
                "resume-fp",
                "parser-fp",
                ProfileStatus.READY,
                List.of(),
                List.of(),
                List.of()
        );
    }

    public static ParserDependencyFingerprint parserDependencyFingerprint() {
        return new ParserDependencyFingerprint("pdf-fp");
    }

    public static RequirementIdentity requirementIdentity() {
        return new RequirementIdentity(jobFingerprint(), parserDependencyFingerprint());
    }

    public static RequirementSet requirementSet(long artifactId) {
        return new RequirementSet(
                artifactId,
                requirementIdentity(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    public static RequirementAssessment assessment(String reqId) {
        return new RequirementAssessment(
                reqId,
                RequirementAssessmentStatus.SUPPORTED,
                List.of("fact-1"),
                "reasoning",
                "gap"
        );
    }

    public static JobAnalysisResult analysisResult() {
        return new JobAnalysisResult(
                JobDecision.RECOMMEND_APPLY,
                "overall assessment",
                List.of(assessment("req-1")),
                List.of("risk-1"),
                List.of("strength-1"),
                "recommendation",
                "summary"
        );
    }

    public static JobContext jobContext() {
        return new JobContext(
                "platform",
                "job-1",
                "title",
                "Shanghai",
                3,
                "3 months",
                "salary",
                "description",
                jobFingerprint()
        );
    }

    public static AnalysisDependencySnapshot analysisDependencySnapshot() {
        return new AnalysisDependencySnapshot(
                jobFingerprint(),
                1L,
                profileIdentity("user-1", 1),
                1L,
                requirementIdentity(),
                "v1",
                null,
                null,
                null,
                null
        );
    }
}
