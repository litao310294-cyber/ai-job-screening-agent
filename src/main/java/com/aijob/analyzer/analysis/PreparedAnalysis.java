package com.aijob.analyzer.analysis;

import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.analysis.identity.AnalysisDependencySnapshot;
import com.aijob.analyzer.analysis.identity.AnalysisIdentity;
import com.aijob.analyzer.profile.domain.CandidateProfile;

/** Values frozen once at request admission and reused throughout execution. */
public record PreparedAnalysis(JobContext job, CandidateProfile profile,
                               AnalysisDependencySnapshot snapshot, AnalysisIdentity identity,
                               com.aijob.analyzer.rules.RuleModels.Snapshot rules,
                               com.aijob.analyzer.workflow.domain.RuleResult ruleResult) {
    public PreparedAnalysis(JobContext job,CandidateProfile profile,AnalysisDependencySnapshot snapshot,AnalysisIdentity identity) {
        this(job,profile,snapshot,identity,null,null);
    }
}
