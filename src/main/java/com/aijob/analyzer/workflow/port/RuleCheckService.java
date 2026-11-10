package com.aijob.analyzer.workflow.port;

import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.workflow.domain.RuleResult;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceRecorder;

public interface RuleCheckService {
    RuleResult check(JobContext job, CandidateProfile profile);
    default RuleResult check(JobContext job, CandidateProfile profile, com.aijob.analyzer.rules.RuleModels.Snapshot rules) {
        return check(job,profile);
    }

    default RuleResult check(JobContext job, CandidateProfile profile, TraceContext ignoredTrace,
            TraceRecorder ignoredRecorder) {
        return check(job, profile);
    }
}
