package com.aijob.analyzer.eval;

import com.aijob.analyzer.analysis.CanonicalCommitResult;
import com.aijob.analyzer.analysis.identity.AnalysisDependencySnapshot;
import com.aijob.analyzer.analysis.identity.AnalysisIdentity;
import com.aijob.analyzer.analysis.identity.AnalysisIdentityFactory;
import com.aijob.analyzer.analysis.identity.JobFingerprint;
import com.aijob.analyzer.analysis.identity.RequirementIdentity;
import com.aijob.analyzer.analysis.identity.RequirementIdentityFactory;
import com.aijob.analyzer.analysis.port.AnalysisResultRepository;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.persistence.model.DbReadResult;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.requirement.domain.RequirementSet;
import com.aijob.analyzer.retrieval.RetrievalSemanticSnapshot;
import com.aijob.analyzer.support.InMemoryTraceRecorder;
import com.aijob.analyzer.support.retry.DefaultRetryExecutor;
import com.aijob.analyzer.support.retry.RetryPolicy;
import com.aijob.analyzer.trace.InProcessTraceRecorder;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceEventType;
import com.aijob.analyzer.trace.TraceRecord;
import com.aijob.analyzer.trace.TraceRecorder;
import com.aijob.analyzer.workflow.AnalysisContext;
import com.aijob.analyzer.workflow.AnalysisOrchestrator;
import com.aijob.analyzer.workflow.AnalysisStage;
import com.aijob.analyzer.workflow.DefaultFallbackResultFactory;
import com.aijob.analyzer.workflow.DefaultRuleTerminalResultFactory;
import com.aijob.analyzer.workflow.DefaultTransitionPolicy;
import com.aijob.analyzer.workflow.StageResult;
import com.aijob.analyzer.workflow.domain.AnalysisExecution;
import com.aijob.analyzer.workflow.domain.ErrorCategory;
import com.aijob.analyzer.workflow.domain.ResultOrigin;
import com.aijob.analyzer.workflow.domain.RuleResult;
import com.aijob.analyzer.workflow.domain.RuleSignal;
import com.aijob.analyzer.workflow.domain.StageType;
import com.aijob.analyzer.workflow.domain.WorkflowError;
import com.aijob.analyzer.workflow.domain.WorkflowStatus;
import com.aijob.analyzer.workflow.stage.CommitResultStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OfflineWorkflowIdentityTraceEvidenceTest {

    @Test
    void writesIdentityFormalMatrix() {
        AnalysisIdentityFactory factory = new AnalysisIdentityFactory();
        var versions = OfflineEvidenceFixtures.versions();
        CandidateProfile profile = OfflineEvidenceFixtures.profile("identity-user", 1L);
        RequirementSet requirements = OfflineEvidenceFixtures.requirementSet(true);
        AnalysisDependencySnapshot base = OfflineEvidenceFixtures.snapshot(profile, requirements, factory, versions);
        AnalysisIdentity same = factory.create(base);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(identityRow("ID-01", same, factory.create(base), true));
        rows.add(identityRow("ID-02", same, factory.create(new AnalysisDependencySnapshot(new JobFingerprint("job-other"), base.profileArtifactId(), base.profileArtifactIdentity(), base.requirementSetId(), base.requirementSetIdentity(), base.ruleVersion(), base.retrievalSemanticFingerprint(), base.retrievalSemanticSnapshot(), base.finalLlmSemanticFingerprint(), base.finalLlmSemanticSnapshot())), false));
        CandidateProfile p2 = OfflineEvidenceFixtures.profile("identity-user", 2L);
        rows.add(identityRow("ID-03", same, factory.create(OfflineEvidenceFixtures.snapshot(p2, requirements, factory, versions)), false));
        var changedVersions = OfflineEvidenceFixtures.versions();
        changedVersions.getRequirementParser().setPrompt("requirement-parser-v2");
        rows.add(identityRow("ID-04", same, factory.create(OfflineEvidenceFixtures.snapshot(profile, requirements, factory, changedVersions)), false));
        RetrievalSemanticSnapshot retrievalV2 = new RetrievalSemanticSnapshot("query-builder-v1", "lucene-standard", "lucene-standard-v1", "offline-embedding", 20, 20, 61, 5);
        rows.add(identityRow("ID-05", same, factory.create(new AnalysisDependencySnapshot(base.jobFingerprint(), base.profileArtifactId(), base.profileArtifactIdentity(), base.requirementSetId(), base.requirementSetIdentity(), base.ruleVersion(), factory.retrievalFingerprint(retrievalV2), retrievalV2, base.finalLlmSemanticFingerprint(), base.finalLlmSemanticSnapshot())), false));
        var llmV2 = OfflineEvidenceFixtures.versions();
        llmV2.getFinalAnalysis().setModel("offline-model-v2");
        rows.add(identityRow("ID-06", same, factory.create(OfflineEvidenceFixtures.snapshot(profile, requirements, factory, llmV2)), false));
        RequirementIdentity reqV2 = new RequirementIdentity(base.requirementSetIdentity().jobFingerprint(), new com.aijob.analyzer.analysis.identity.ParserDependencyFingerprint("requirement-parser-v2"));
        rows.add(identityRow("ID-07", same, factory.create(new AnalysisDependencySnapshot(base.jobFingerprint(), base.profileArtifactId(), base.profileArtifactIdentity(), base.requirementSetId(), reqV2, base.ruleVersion(), base.retrievalSemanticFingerprint(), base.retrievalSemanticSnapshot(), base.finalLlmSemanticFingerprint(), base.finalLlmSemanticSnapshot())), false));
        long passed = rows.stream().filter(row -> Boolean.TRUE.equals(row.get("pass"))).count();
        Map<String, Object> artifact = new LinkedHashMap<>(); artifact.put("matrix", "IDENTITY_OFFLINE_V1"); artifact.put("cases", rows); artifact.put("overall", passed + "/" + rows.size());
        writeJson("identity_raw.json", artifact);
        assertEquals(7, passed);
    }

    private Map<String, Object> identityRow(String id, AnalysisIdentity before, AnalysisIdentity after, boolean expectedSame) {
        boolean actualSame = before.equals(after);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("caseId", id); row.put("safeBeforeFingerprint", safe(before.analysisDependencyFingerprint().value()));
        row.put("safeAfterFingerprint", safe(after.analysisDependencyFingerprint().value())); row.put("expectedSame", expectedSame);
        row.put("actualSame", actualSame); row.put("pass", actualSame == expectedSame); return row;
    }

    @Test
    void writesWorkflowFormalMatrix() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(workflowCase("WF-01", "NORMAL_SUCCESS"));
        rows.add(workflowCase("WF-02", "HARD_REJECT"));
        rows.add(workflowCase("WF-03", "NO_EVIDENCE"));
        rows.add(workflowCase("WF-04", "SCHEMA_FAILURE"));
        rows.add(workflowCase("WF-05", "PARSE_FAILURE"));
        rows.add(workflowCase("WF-06", "GROUNDING_FAILURE"));
        rows.add(workflowCase("WF-07", "RETRY_THEN_SUCCESS"));
        rows.add(workflowCase("WF-08", "EXHAUSTED_RETRY_FALLBACK"));
        rows.add(workflowCase("WF-09", "INTERNAL_FAILURE"));
        rows.add(workflowCase("WF-10", "PERSISTENCE_FAILURE"));
        rows.add(workflowCase("WF-11", "COMMIT_UNCERTAIN_READBACK_FOUND"));
        rows.add(workflowCase("WF-12", "COMMIT_UNCERTAIN_READBACK_MISSING"));
        long passed = rows.stream().filter(row -> Boolean.TRUE.equals(row.get("pass"))).count();
        Map<String, Object> artifact = new LinkedHashMap<>(); artifact.put("matrix", "WORKFLOW_OFFLINE_V1"); artifact.put("cases", rows); artifact.put("overall", passed + "/" + rows.size());
        writeJson("workflow_raw.json", artifact);
        assertEquals(12, passed);
    }

    private Map<String, Object> workflowCase(String id, String scenario) {
        InMemoryTraceRecorder recorder = new InMemoryTraceRecorder();
        AnalysisContext context = OfflineEvidenceFixtures.context("trace-" + id, "task-" + id, recorder);
        EnumMap<StageType, AtomicInteger> counts = new EnumMap<>(StageType.class);
        for (StageType type : StageType.values()) counts.put(type, new AtomicInteger());
        AnalysisResultRepository repository = mock(AnalysisResultRepository.class);
        AnalysisStage commit = OfflineEvidenceFixtures.stage(StageType.COMMIT_RESULT, c -> StageResult.success());
        if (scenario.startsWith("COMMIT_UNCERTAIN")) {
            AnalysisResultRepository commitRepo = new AnalysisResultRepository() {
                @Override public DbReadResult<com.aijob.analyzer.analysis.domain.AnalysisArtifact> findCanonical(AnalysisIdentity id) { return new DbReadResult<>(DbReadOutcome.NOT_FOUND, null, null); }
                @Override public CanonicalCommitResult commitCanonical(AnalysisIdentity id, com.aijob.analyzer.analysis.domain.JobAnalysisResult value, AnalysisDependencySnapshot snapshot) { return new CanonicalCommitResult.Uncertain("DUPLICATE_UNKNOWN"); }
                @Override public DbReadResult<com.aijob.analyzer.analysis.domain.AnalysisArtifact> readBack(AnalysisIdentity id) {
                    if (scenario.endsWith("FOUND")) return new DbReadResult<>(DbReadOutcome.FOUND, OfflineEvidenceFixtures.artifact(id, snapshot(context), true), null);
                    return new DbReadResult<>(DbReadOutcome.NOT_FOUND, null, null);
                }
            };
            commit = new CommitResultStage(commitRepo, recorder);
        }
        AnalysisStage finalCommit = commit;
        Map<StageType, AnalysisStage> stages = new EnumMap<>(StageType.class);
        stages.put(StageType.RULE_CHECK, counted(counts, StageType.RULE_CHECK, c -> {
            if (scenario.equals("HARD_REJECT")) { c.setRuleResult(new RuleResult(RuleSignal.HARD_REJECT, List.of("location"))); return StageResult.hardReject(); }
            c.setRuleResult(new RuleResult(RuleSignal.CONTINUE, List.of())); return StageResult.success();
        }));
        stages.put(StageType.PROFILE_RETRIEVAL, counted(counts, StageType.PROFILE_RETRIEVAL, c -> {
            c.setRequirementSet(OfflineEvidenceFixtures.requirementSet(!scenario.equals("NO_EVIDENCE")));
            c.setRetrievalResult(OfflineEvidenceFixtures.retrievalResult(!scenario.equals("NO_EVIDENCE")));
            return scenario.equals("NO_EVIDENCE") ? StageResult.noEvidence() : StageResult.success();
        }));
        AtomicInteger llmCalls = new AtomicInteger();
        stages.put(StageType.LLM_ANALYSIS, counted(counts, StageType.LLM_ANALYSIS, c -> {
            llmCalls.incrementAndGet();
            if (scenario.equals("RETRY_THEN_SUCCESS") && llmCalls.get() == 1) return StageResult.failed(new WorkflowError("LLM_TIMEOUT", ErrorCategory.PROVIDER, true, "timeout"));
            if (scenario.equals("EXHAUSTED_RETRY_FALLBACK")) return StageResult.failed(new WorkflowError("LLM_TIMEOUT", ErrorCategory.PROVIDER, true, "timeout"));
            if (scenario.equals("SCHEMA_FAILURE")) return StageResult.failed(new WorkflowError("LLM_SCHEMA_FAILED", ErrorCategory.SCHEMA, false, "schema"));
            if (scenario.equals("PARSE_FAILURE")) return StageResult.failed(new WorkflowError("LLM_PARSE_FAILED", ErrorCategory.PARSE, false, "parse"));
            if (scenario.equals("GROUNDING_FAILURE")) return StageResult.failed(new WorkflowError("LLM_GROUNDING_FAILED", ErrorCategory.GROUNDING, false, "grounding"));
            if (scenario.equals("INTERNAL_FAILURE")) throw new RuntimeException("bug");
            if (scenario.equals("PERSISTENCE_FAILURE")) return StageResult.failed(new WorkflowError("PERSISTENCE_BOUNDARY", ErrorCategory.PERSISTENCE, false, "persistence"));
            c.setRawResult(OfflineEvidenceFixtures.validResult(!scenario.equals("NO_EVIDENCE"))); return StageResult.success();
        }));
        stages.put(StageType.RESULT_VALIDATE, counted(counts, StageType.RESULT_VALIDATE, c -> { c.setValidatedResult(c.rawResult()); return StageResult.success(); }));
        stages.put(StageType.COMMIT_RESULT, counted(counts, StageType.COMMIT_RESULT, c -> finalCommit.execute(c)));
        Map<StageType, RetryPolicy> policies = new EnumMap<>(StageType.class);
        for (StageType type : StageType.values()) policies.put(type, new RetryPolicy(type == StageType.LLM_ANALYSIS ? 3 : 1, Duration.ZERO, 1.0, Duration.ZERO, 0.0));
        AnalysisOrchestrator orchestrator = new AnalysisOrchestrator(stages, new DefaultTransitionPolicy(), new DefaultRuleTerminalResultFactory(), new DefaultFallbackResultFactory(), recorder, new DefaultRetryExecutor(duration -> {}, () -> 0.0), policies);
        AnalysisExecution execution = orchestrator.execute(context);
        WorkflowStatus expectedStatus = switch (scenario) {
            case "HARD_REJECT" -> WorkflowStatus.SUCCESS;
            case "EXHAUSTED_RETRY_FALLBACK" -> WorkflowStatus.DEGRADED;
            case "COMMIT_UNCERTAIN_READBACK_FOUND", "NORMAL_SUCCESS", "NO_EVIDENCE", "RETRY_THEN_SUCCESS" -> WorkflowStatus.SUCCESS;
            default -> WorkflowStatus.FAILED;
        };
        boolean pass = execution.status() == expectedStatus && ((scenario.equals("RETRY_THEN_SUCCESS") && llmCalls.get() == 2) || (!scenario.equals("RETRY_THEN_SUCCESS") && !scenario.equals("EXHAUSTED_RETRY_FALLBACK") || llmCalls.get() == 3));
        Map<String, Object> row = new LinkedHashMap<>(); row.put("caseId", id); row.put("scenario", scenario); row.put("workflowStatus", execution.status().name()); row.put("resultOrigin", String.valueOf(execution.resultOrigin())); row.put("ruleCalls", counts.get(StageType.RULE_CHECK).get()); row.put("retrievalCalls", counts.get(StageType.PROFILE_RETRIEVAL).get()); row.put("llmCalls", llmCalls.get()); row.put("validationCalls", counts.get(StageType.RESULT_VALIDATE).get()); row.put("commitCalls", counts.get(StageType.COMMIT_RESULT).get()); row.put("expectedStatus", expectedStatus.name()); row.put("pass", pass); return row;
    }

    private AnalysisDependencySnapshot snapshot(AnalysisContext context) { return context.dependencySnapshot(); }

    private AnalysisStage counted(Map<StageType, AtomicInteger> counts, StageType type, java.util.function.Function<AnalysisContext, StageResult> operation) {
        return OfflineEvidenceFixtures.stage(type, context -> { counts.get(type).incrementAndGet(); return operation.apply(context); });
    }

    @Test
    void writesTraceSamples() {
        InProcessTraceRecorder recorder = new InProcessTraceRecorder();
        List<Map<String, Object>> samples = new ArrayList<>();
        TraceEventType[] events = {TraceEventType.CANONICAL_REUSE, TraceEventType.CANONICAL_REUSE, TraceEventType.TRANSITION, TraceEventType.RETRY_SCHEDULED, TraceEventType.REPAIR_APPLIED, TraceEventType.FALLBACK_SELECTED, TraceEventType.SINGLEFLIGHT_LEADER, TraceEventType.SINGLEFLIGHT_FOLLOWER};
        String[] stages = {"CACHE", "DATABASE", "WORKFLOW", "LLM_ANALYSIS", "LLM_ANALYSIS", "LLM_ANALYSIS", "SINGLEFLIGHT", "SINGLEFLIGHT"};
        for (int i = 0; i < events.length; i++) {
            String traceId = "trace-sample-" + (i + 1); TraceContext context = new TraceContext(traceId, "task-sample-" + (i + 1));
            recorder.event(context, events[i], Map.of("stage", stages[i], "outcome", "OBSERVED", "sampleIndex", i + 1));
            TraceRecord record = recorder.getRecords(traceId).getFirst();
            Map<String, Object> sample = new LinkedHashMap<>(); sample.put("sequence", record.sequence()); sample.put("safeTraceId", safe(record.traceId())); sample.put("safeTaskId", safe(record.taskId())); sample.put("stage", stages[i]); sample.put("event", events[i].name()); sample.put("attempt", 1); sample.put("outcome", "OBSERVED"); sample.put("metadata", Map.of("sampleIndex", i + 1)); sample.put("pass", record.sequence() == 1); samples.add(sample);
        }
        Map<String, Object> artifact = new LinkedHashMap<>(); artifact.put("matrix", "TRACE_OFFLINE_V1"); artifact.put("samples", samples); artifact.put("overall", samples.stream().filter(s -> Boolean.TRUE.equals(s.get("pass"))).count() + "/" + samples.size());
        writeJson("trace_samples.json", artifact);
        assertEquals(8, samples.stream().filter(s -> Boolean.TRUE.equals(s.get("pass"))).count());
    }

    private String safe(String value) {
        if (value.length() <= 12) return value;
        return value.substring(0, 8) + "…" + value.substring(value.length() - 3);
    }

    private void writeJson(String filename, Object value) {
        try { Files.createDirectories(Path.of("docs/eval/results")); new ObjectMapper().writeValue(Path.of("docs/eval/results", filename).toFile(), value); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
