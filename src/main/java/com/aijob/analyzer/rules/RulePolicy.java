package com.aijob.analyzer.rules;

import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.workflow.domain.RuleResult;
import com.aijob.analyzer.workflow.port.RuleCheckService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service @Profile("!test")
public class RulePolicy {
    private final RuleConfigService configs;
    private final RuleCheckService engine;
    public RulePolicy(RuleConfigService configs,RuleCheckService engine) { this.configs=configs;this.engine=engine; }
    public record Frozen(RuleModels.Snapshot snapshot,RuleResult result) {}
    public Frozen freeze(JobContext job,CandidateProfile profile) {
        var snapshot=configs.current();
        return new Frozen(snapshot,engine.check(job,profile,snapshot));
    }
}
