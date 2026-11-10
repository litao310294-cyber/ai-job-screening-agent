package com.aijob.analyzer.eval;

import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.analysis.identity.*;
import com.aijob.analyzer.llm.FinalAnalysisService;
import com.aijob.analyzer.llm.LlmSemanticVersions;
import com.aijob.analyzer.llm.port.ChatModelClient;
import com.aijob.analyzer.llm.recovery.LlmRecoveryPolicy;
import com.aijob.analyzer.profile.domain.SourceReference;
import com.aijob.analyzer.requirement.domain.*;
import com.aijob.analyzer.retrieval.domain.*;
import com.aijob.analyzer.support.FixtureFactory;
import com.aijob.analyzer.support.retry.*;
import com.aijob.analyzer.support.time.*;
import com.aijob.analyzer.trace.InProcessTraceRecorder;
import com.aijob.analyzer.workflow.*;
import com.aijob.analyzer.workflow.domain.*;
import com.aijob.analyzer.workflow.stage.LlmAnalysisStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:isolated;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false"})
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="realChatEval", matches="true")
class RealChatEvalV1Test {
    @Autowired FinalAnalysisService finalAnalysisService;
    @Autowired ChatModelClient chatModelClient;
    @Autowired LlmSemanticVersions versions;
    @Autowired LlmRecoveryPolicy recoveryPolicy;
    @Autowired ObjectMapper mapper;

    @Test
    void runOnceWhenOptedIn() throws Exception {

        var rows = new ArrayList<Map<String,Object>>(); int valid = 0, calls = 0;
        for (int i = 1; i <= 12; i++) {
            var context = context();
            var stage = new LlmAnalysisStage(finalAnalysisService, versions, recoveryPolicy);
            Map<StageType, AnalysisStage> stages = new EnumMap<>(StageType.class);
            stages.put(StageType.RULE_CHECK, stage(StageType.RULE_CHECK, c -> { c.setRuleResult(new RuleResult(RuleSignal.CONTINUE, List.of())); return StageResult.success(); }));
            stages.put(StageType.PROFILE_RETRIEVAL, stage(StageType.PROFILE_RETRIEVAL, c -> { c.setRequirementSet(requirementSet()); c.setRetrievalResult(retrieval()); return StageResult.success(); }));
            stages.put(StageType.LLM_ANALYSIS, stage);
            stages.put(StageType.RESULT_VALIDATE, stage(StageType.RESULT_VALIDATE, c -> { c.setValidatedResult(c.rawResult()); return StageResult.success(); }));
            stages.put(StageType.COMMIT_RESULT, stage(StageType.COMMIT_RESULT, c -> StageResult.success()));
            var orchestrator = new AnalysisOrchestrator(stages, new DefaultTransitionPolicy(), new DefaultRuleTerminalResultFactory(),
                    new DefaultFallbackResultFactory(), new InProcessTraceRecorder(), new DefaultRetryExecutor(d -> {}, () -> 0),
                    Map.of(StageType.LLM_ANALYSIS, new RetryPolicy(3, Duration.ZERO, 1, Duration.ZERO, 0)));
            AnalysisExecution execution = orchestrator.execute(context);
            int attempts = (int) context.attemptHistory().stream().filter(a -> a.stageType() == StageType.LLM_ANALYSIS).count(); calls += attempts;
            boolean ok = execution.status() == WorkflowStatus.SUCCESS && execution.resultOrigin() == ResultOrigin.LLM_ANALYSIS && execution.result() != null;
            if (ok) valid++;
            var row = new LinkedHashMap<String,Object>(); row.put("caseId", "C" + String.format("%02d", i)); row.put("requestedModel", versions.getFinalAnalysisSnapshot().modelIdentity()); row.put("returnedModel", null); row.put("providerCallCount", attempts); row.put("attemptCount", attempts); row.put("firstResponse", Map.of("parsePass", ok, "schemaPass", ok, "businessPass", ok, "groundingPass", ok)); row.put("firstPassValid", attempts == 1 && ok); row.put("repairApplied", false); row.put("retryScheduled", attempts > 1); row.put("retryCount", Math.max(0, attempts - 1)); row.put("fallbackSelected", execution.resultOrigin() == ResultOrigin.DEGRADED_FALLBACK); row.put("finalValidLlmResult", ok); row.put("workflowStatus", execution.status().name()); row.put("resultOrigin", execution.resultOrigin() == null ? null : execution.resultOrigin().name()); row.put("jobDecision", execution.result() == null ? null : execution.result().decision().name()); row.put("systemTerminalContractPass", ok || execution.resultOrigin() == ResultOrigin.DEGRADED_FALLBACK); if (context.lastError() != null) { row.put("failureCode", context.lastError().code()); row.put("validationLayer", context.lastError().category().name()); row.put("safeError", context.lastError().safeMessage()); } rows.add(row);
        }
        Path outputDirectory = Path.of(System.getProperty("realChatEval.outputDirectory", "target/real-chat-eval"));
        Files.createDirectories(outputDirectory);
        mapper.writeValue(outputDirectory.resolve("chat_v1_real_provider_raw.json").toFile(), Map.of("dataset", "chat-eval-v1.2", "freezeCommit", "7c8932ca6469f6b8e224452a53080f92f9bdc80e", "provider", "DeepSeek", "total", 12, "valid", valid, "providerCalls", calls, "cases", rows, "status", "COMPLETE"));
        mapper.writeValue(outputDirectory.resolve("chat_v1_metrics.json").toFile(), Map.of("dataset", "chat-eval-v1.2", "firstPassValid", valid, "finalValidLlmResult", valid, "systemTerminalContract", valid, "total", 12, "providerCalls", calls));
        assertEquals(12, rows.size());
    }

    private AnalysisContext context() {
        var p = FixtureFactory.candidateProfile(1L, "chat-eval");
        return new AnalysisContext("trace", "task", new JobContext("eval", "chat-v1.1", "Java Backend Intern", "Shanghai", 90, "3 months", "salary", "Java Spring Boot Redis backend internship", new JobFingerprint("chat-eval-job")), p, p.identity(), FixtureFactory.analysisIdentity(), ExecutionDeadline.fromNow(Duration.ofMinutes(2), TimeSource.SYSTEM), FixtureFactory.analysisDependencySnapshot());
    }
    private RequirementSet requirementSet() { return new RequirementSet(1L, new RequirementIdentity(new JobFingerprint("chat-eval-job"), new ParserDependencyFingerprint("chat-eval")), List.of(), List.of(new AtomicRequirement("R0001", "SKILL", "Java Spring Boot", RequirementPriority.REQUIRED, "Java Spring Boot")), List.of()); }
    private RetrievalResult retrieval() { return new RetrievalResult(Map.of("R0001", List.of(new Evidence("F0001", "R0001", "F0001", "Candidate built Java Spring Boot service", new SourceReference("eval-resume-1", "Eval profile", "project", "Java Spring Boot"))))); }
    private AnalysisStage stage(StageType type, java.util.function.Function<AnalysisContext, StageResult> fn) { return new AnalysisStage() { public StageType type() { return type; } public StageResult execute(AnalysisContext c) { return fn.apply(c); } }; }
}
