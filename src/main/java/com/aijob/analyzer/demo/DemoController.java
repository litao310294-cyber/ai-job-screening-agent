package com.aijob.analyzer.demo;

import com.aijob.analyzer.profile.CurrentProfileResolver;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.aijob.analyzer.demo.DemoModels.*;

@RestController @RequestMapping("/api") @Profile("!test")
public class DemoController {
    private final JobSnapshotService jobs;
    private final AnalysisHttpService analyses;
    private final AnalysisOperationRepository operations;
    private final CurrentProfileResolver profiles;
    private final com.aijob.analyzer.profile.port.CandidateProfileRepository profileRepository;
    @org.springframework.beans.factory.annotation.Value("${app.demo.provider-mode:REAL}")
    private String providerMode;
    public DemoController(JobSnapshotService jobs, AnalysisHttpService analyses, AnalysisOperationRepository operations,
                          CurrentProfileResolver profiles, com.aijob.analyzer.profile.port.CandidateProfileRepository profileRepository) {
        this.jobs = jobs; this.analyses = analyses; this.operations = operations; this.profiles = profiles;
        this.profileRepository = profileRepository;
    }
    @GetMapping("/health") public Object health() { return Map.of("status", "UP"); }
    @GetMapping("/session") public Object session() {
        var result = new LinkedHashMap<String,Object>();
        result.put("userId", USER_ID); result.put("displayName", "李涛"); result.put("ruleMode", "VERSIONED_STRUCTURED");
        result.put("providerMode", providerMode);
        result.put("profile", profiles.resolveCurrent(USER_ID).map(p -> Map.of("artifactId", p.artifactId(), "version", p.identity().profileVersion())).orElse(null));
        return result;
    }
    @PostMapping("/jobs") public ResponseEntity<JobSnapshot> save(@Valid @RequestBody JobInput input) {
        return ResponseEntity.status(201).body(jobs.save(input));
    }
    @GetMapping("/jobs") public Object jobs() { return jobs.recent(); }
    @GetMapping("/jobs/{id}") public Object job(@PathVariable String id) { return jobs.get(id); }
    @PostMapping("/analyses") public ResponseEntity<Operation> analyze(@Valid @RequestBody AnalysisInput input) {
        var result = analyses.submit(input);
        return ResponseEntity.status("RUNNING".equals(result.status()) ? 202 : 200).body(result);
    }
    @GetMapping("/analyses") public Object analyses() { return operations.recent(); }
    @GetMapping("/analyses/{id}") public Object analysis(@PathVariable String id) { return operations.get(id); }
    @GetMapping("/analyses/{id}/trace") public Object trace(@PathVariable String id) { return operations.trace(id); }
    @GetMapping("/analyses/{id}/evidence") public Object evidence(@PathVariable String id) {
        var operation = operations.get(id);
        var profile = profileRepository.find(operation.dependencies().profileArtifactIdentity()).value();
        if (profile == null || profile.artifactId() != operation.dependencies().profileArtifactId()) {
            throw new DemoException(409, "ANALYSIS_EVIDENCE_UNAVAILABLE", "该分析绑定的画像证据不可用");
        }
        return Map.of("profileIdentity", profile.identity(), "profileArtifactId", profile.artifactId(), "facts", profile.retrievalFacts());
    }
}
