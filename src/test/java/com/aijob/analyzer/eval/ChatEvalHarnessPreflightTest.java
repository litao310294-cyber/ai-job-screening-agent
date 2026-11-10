package com.aijob.analyzer.eval;

import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.llm.FinalAnalysisService;
import com.aijob.analyzer.llm.LlmSemanticVersions;
import com.aijob.analyzer.llm.port.ChatModelClient;
import com.aijob.analyzer.llm.port.ChatModelRequest;
import com.aijob.analyzer.llm.port.ChatModelResponse;
import org.mockito.Mockito;
import static org.mockito.ArgumentMatchers.any;
import com.aijob.analyzer.llm.recovery.LlmRecoveryPolicy;
import com.aijob.analyzer.llm.validation.DefaultJsonContractParser;
import com.aijob.analyzer.llm.validation.DefaultSafeJsonRepair;
import com.aijob.analyzer.analysis.validation.JobAnalysisResultValidator;
import com.aijob.analyzer.support.FixtureFactory;
import com.aijob.analyzer.support.InMemoryTraceRecorder;
import com.aijob.analyzer.support.retry.DefaultRetryExecutor;
import com.aijob.analyzer.support.retry.RetryExecutor;
import com.aijob.analyzer.support.retry.RetryPolicy;
import com.aijob.analyzer.support.time.ExecutionDeadline;
import com.aijob.analyzer.support.time.TimeSource;
import com.aijob.analyzer.workflow.*;
import com.aijob.analyzer.workflow.domain.*;
import com.aijob.analyzer.workflow.stage.LlmAnalysisStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.aijob.analyzer.profile.port.CandidateProfileRepository;
import com.aijob.analyzer.retrieval.ProfileProjectionService;
import com.aijob.analyzer.requirement.port.RequirementSetRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:isolated;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false"})
@ActiveProfiles("test")
class ChatEvalHarnessPreflightTest {
    @Autowired DefaultJsonContractParser parser;
    @Autowired DefaultSafeJsonRepair repair;
    @Autowired JobAnalysisResultValidator validator;
    @Autowired LlmSemanticVersions versions;
    @Autowired ObjectMapper mapper;
    @Autowired LlmRecoveryPolicy recoveryPolicy;
    @Autowired FinalAnalysisService productionService;
    @MockBean ChatModelClient chatClient;
    @MockBean CandidateProfileRepository candidateProfileRepository;
    @MockBean ProfileProjectionService profileProjectionService;
    @MockBean RequirementSetRepository requirementSetRepository;
    @MockBean RetryExecutor retryExecutor;

    @Test
    void productionPathPreflight() throws Exception {
        if (!Boolean.getBoolean("chatHarnessPreflight")) return;
        var rows = new ArrayList<Map<String,Object>>();
        rows.add(run("first-pass", () -> validJson(), false));
        rows.add(run("repair", () -> "```json\n" + validJson() + "\n```", false));
        rows.add(run("retry", new ProviderMode() { int n; public String next() { if (n++ == 0) throw new com.aijob.analyzer.llm.error.ProviderTransportException("timeout", null); return validJson(); } }, false));
        rows.add(run("fallback", () -> { throw new com.aijob.analyzer.llm.error.ProviderTransportException("timeout", null); }, true));
        rows.add(run("internal-failure", () -> { throw new RuntimeException("bug"); }, true));
        Files.createDirectories(Path.of("docs/eval/results"));
        mapper.writeValue(Path.of("docs/eval/results/chat_v1_harness_preflight.json").toFile(), Map.of("status", "PASS", "cases", rows));
        assertEquals(5, rows.size());
    }

    private Map<String,Object> run(String name, ProviderMode mode, boolean expectFailure) {
        Mockito.reset(chatClient);
        Mockito.when(chatClient.generate(any(ChatModelRequest.class))).thenAnswer(inv -> new ChatModelResponse(mode.next(), "preflight", "fake", null, null, null, "stop"));
        var stage = new LlmAnalysisStage(productionService, versions, recoveryPolicy);
        var context = newContext();
        Map<StageType, AnalysisStage> stages = new EnumMap<>(StageType.class);
        stages.put(StageType.RULE_CHECK, stage(StageType.RULE_CHECK, c -> { c.setRuleResult(new RuleResult(RuleSignal.CONTINUE, List.of())); return StageResult.success(); }));
        stages.put(StageType.PROFILE_RETRIEVAL, stage(StageType.PROFILE_RETRIEVAL, c -> { c.setRequirementSet(FixtureFactory.requirementSet(1)); c.setRetrievalResult(new com.aijob.analyzer.retrieval.domain.RetrievalResult(Map.of())); return StageResult.success(); }));
        stages.put(StageType.LLM_ANALYSIS, stage);
        stages.put(StageType.RESULT_VALIDATE, stage(StageType.RESULT_VALIDATE, c -> { c.setValidatedResult(c.rawResult()); return StageResult.success(); }));
        stages.put(StageType.COMMIT_RESULT, stage(StageType.COMMIT_RESULT, c -> StageResult.success()));
        var orchestrator = new AnalysisOrchestrator(stages, new DefaultTransitionPolicy(), new DefaultRuleTerminalResultFactory(),
                new DefaultFallbackResultFactory(), new InMemoryTraceRecorder(), new DefaultRetryExecutor(d -> {}, () -> 0),
                Map.of(StageType.LLM_ANALYSIS, new RetryPolicy(3, Duration.ZERO, 1, Duration.ZERO, 0)));
        AnalysisExecution execution = orchestrator.execute(context);
        if (name.equals("first-pass") || name.equals("repair") || name.equals("retry")) {
            if (!expectFailure) assertEquals(WorkflowStatus.SUCCESS, execution.status());
        } else if (name.equals("fallback")) assertEquals(WorkflowStatus.DEGRADED, execution.status());
        else assertEquals(WorkflowStatus.FAILED, execution.status());
        return Map.of("case", name, "workflowStatus", execution.status().name(), "resultOrigin", String.valueOf(execution.resultOrigin()), "attempts", context.attemptHistory().size());
    }

    private AnalysisContext newContext() {
        var p = FixtureFactory.candidateProfile(1L, "preflight");
        return new AnalysisContext("trace", "task", FixtureFactory.jobContext(), p, p.identity(), FixtureFactory.analysisIdentity(),
                ExecutionDeadline.fromNow(Duration.ofSeconds(30), TimeSource.SYSTEM), FixtureFactory.analysisDependencySnapshot());
    }

    private String validJson() { return "{\"decision\":\"INSUFFICIENT_EVIDENCE\",\"overallAssessment\":\"insufficient\",\"requirementAssessments\":[],\"risks\":[],\"strengths\":[],\"recommendation\":\"review\",\"summary\":\"insufficient evidence\"}"; }

    private AnalysisStage stage(StageType type, java.util.function.Function<AnalysisContext, StageResult> fn) {
        return new AnalysisStage() { public StageType type() { return type; } public StageResult execute(AnalysisContext c) { return fn.apply(c); } };
    }

    @FunctionalInterface interface ProviderMode { String next(); }
}
