package com.aijob.analyzer.eval;

import com.aijob.analyzer.llm.FinalAnalysisService;
import com.aijob.analyzer.llm.LlmSemanticVersions;
import com.aijob.analyzer.llm.error.ProviderError;
import com.aijob.analyzer.llm.error.ProviderTransportException;
import com.aijob.analyzer.llm.port.ChatModelClient;
import com.aijob.analyzer.llm.port.ChatModelRequest;
import com.aijob.analyzer.llm.port.ChatModelResponse;
import com.aijob.analyzer.support.InMemoryTraceRecorder;
import com.aijob.analyzer.support.retry.DefaultRetryExecutor;
import com.aijob.analyzer.support.retry.RetryPolicy;
import com.aijob.analyzer.support.time.ExecutionDeadline;
import com.aijob.analyzer.support.time.TimeSource;
import com.aijob.analyzer.trace.TraceEventType;
import com.aijob.analyzer.trace.TraceRecord;
import com.aijob.analyzer.workflow.AnalysisContext;
import com.aijob.analyzer.workflow.AnalysisOrchestrator;
import com.aijob.analyzer.workflow.AnalysisStage;
import com.aijob.analyzer.workflow.DefaultFallbackResultFactory;
import com.aijob.analyzer.workflow.DefaultRuleTerminalResultFactory;
import com.aijob.analyzer.workflow.DefaultTransitionPolicy;
import com.aijob.analyzer.workflow.StageResult;
import com.aijob.analyzer.workflow.domain.StageType;
import com.aijob.analyzer.workflow.domain.WorkflowStatus;
import com.aijob.analyzer.llm.recovery.LlmRecoveryPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:isolated;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false"})
@ActiveProfiles("test")
class OfflineReliabilityEvidenceTest {
    @Autowired FinalAnalysisService finalAnalysisService;
    @Autowired LlmSemanticVersions versions;
    @Autowired LlmRecoveryPolicy recoveryPolicy;
    @Autowired ObjectMapper mapper;
    @MockBean ChatModelClient chatClient;
    @MockBean com.aijob.analyzer.profile.port.CandidateProfileRepository candidateProfileRepository;
    @MockBean com.aijob.analyzer.retrieval.ProfileProjectionService profileProjectionService;
    @MockBean com.aijob.analyzer.requirement.port.RequirementSetRepository requirementSetRepository;
    @MockBean com.aijob.analyzer.support.retry.RetryExecutor retryExecutor;

    @Test
    void writesReliabilityFormalMatrix() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(run("REL-01", "PROVIDER_429", List.of(new ProviderError(429, "RATE_LIMIT", "rate limited"), response(true)), true, "SUCCESS"));
        rows.add(run("REL-02", "PROVIDER_503", List.of(new ProviderError(503, "UNAVAILABLE", "unavailable"), response(true)), true, "SUCCESS"));
        rows.add(run("REL-03", "TRANSPORT_TIMEOUT", List.of(new ProviderTransportException("timeout", null), response(true)), true, "SUCCESS"));
        rows.add(run("REL-04", "BOM_WHITESPACE", List.of(response("\uFEFF  " + OfflineEvidenceFixtures.validJson(true) + "  ")), true, "SUCCESS"));
        rows.add(run("REL-05", "OUTER_MARKDOWN_FENCE", List.of(response("```json\n" + OfflineEvidenceFixtures.validJson(true) + "\n```")), true, "SUCCESS"));
        rows.add(run("REL-06", "PARTIAL_STATUS_ALIAS", List.of(response(OfflineEvidenceFixtures.validJson(true).replace("SUPPORTED", "PARTIAL"))), true, "SUCCESS"));
        rows.add(run("REL-07", "TRUNCATED_JSON", List.of(response("{\"decision\":\"RECOMMEND_APPLY\"")), true, "FAILED"));
        rows.add(run("REL-08", "MALFORMED_JSON", List.of(response("not-json")), true, "FAILED"));
        rows.add(run("REL-09", "MISSING_REQUIRED_DECISION", List.of(response("{\"overallAssessment\":\"missing\",\"requirementAssessments\":[],\"risks\":[],\"strengths\":[],\"recommendation\":\"review\",\"summary\":\"missing\"}")), true, "FAILED"));
        rows.add(run("REL-10", "UNKNOWN_EVIDENCE_REF", List.of(response(OfflineEvidenceFixtures.validJson(true).replace("F0001", "F9999"))), true, "FAILED"));
        rows.add(run("REL-11", "PERSISTENT_RETRYABLE_PROVIDER", List.of(new ProviderError(503, "UNAVAILABLE", "unavailable"), new ProviderError(503, "UNAVAILABLE", "unavailable"), new ProviderError(503, "UNAVAILABLE", "unavailable")), true, "DEGRADED"));
        rows.add(run("REL-12", "INTERNAL_RUNTIME_FAILURE", List.of(new RuntimeException("offline internal failure")), true, "FAILED"));

        long passed = rows.stream().filter(row -> Boolean.TRUE.equals(row.get("pass"))).count();
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("matrix", "LLM_RELIABILITY_OFFLINE_V1");
        artifact.put("providerCalls", rows.stream().mapToInt(row -> ((Number) row.get("providerCalls")).intValue()).sum());
        artifact.put("cases", rows);
        artifact.put("overall", passed + "/" + rows.size());
        Files.createDirectories(Path.of("docs/eval/results"));
        mapper.writeValue(Path.of("docs/eval/results/reliability_offline_raw.json").toFile(), artifact);
        assertEquals(12, rows.size());
        assertEquals(12, passed);
    }

    private Map<String, Object> run(String id, String injectedFailure, List<Object> script,
                                    boolean withEvidence, String expectedTerminal) {
        reset(chatClient);
        AtomicInteger index = new AtomicInteger();
        when(chatClient.generate(any(ChatModelRequest.class))).thenAnswer(invocation -> {
            Object next = script.get(index.getAndIncrement());
            if (next instanceof RuntimeException failure) throw failure;
            return next;
        });

        InMemoryTraceRecorder recorder = new InMemoryTraceRecorder();
        AnalysisContext context = OfflineEvidenceFixtures.context("trace-" + id, "task-" + id, recorder);
        AnalysisStage llm = new com.aijob.analyzer.workflow.stage.LlmAnalysisStage(finalAnalysisService, versions, recoveryPolicy);
        Map<StageType, AnalysisStage> stages = new EnumMap<>(StageType.class);
        stages.put(StageType.RULE_CHECK, OfflineEvidenceFixtures.stage(StageType.RULE_CHECK, c -> {
            c.setRuleResult(new com.aijob.analyzer.workflow.domain.RuleResult(com.aijob.analyzer.workflow.domain.RuleSignal.CONTINUE, List.of()));
            return StageResult.success();
        }));
        stages.put(StageType.PROFILE_RETRIEVAL, OfflineEvidenceFixtures.stage(StageType.PROFILE_RETRIEVAL, c -> {
            c.setRequirementSet(OfflineEvidenceFixtures.requirementSet(withEvidence));
            c.setRetrievalResult(OfflineEvidenceFixtures.retrievalResult(withEvidence));
            return StageResult.success();
        }));
        stages.put(StageType.LLM_ANALYSIS, llm);
        stages.put(StageType.RESULT_VALIDATE, OfflineEvidenceFixtures.stage(StageType.RESULT_VALIDATE, c -> {
            c.setValidatedResult(c.rawResult());
            return StageResult.success();
        }));
        stages.put(StageType.COMMIT_RESULT, OfflineEvidenceFixtures.stage(StageType.COMMIT_RESULT, c -> StageResult.success()));
        Map<StageType, RetryPolicy> policies = new EnumMap<>(StageType.class);
        for (StageType type : StageType.values()) policies.put(type,
                new RetryPolicy(type == StageType.LLM_ANALYSIS ? 3 : 1, Duration.ZERO, 1.0, Duration.ZERO, 0.0));
        AnalysisOrchestrator orchestrator = new AnalysisOrchestrator(stages, new DefaultTransitionPolicy(),
                new DefaultRuleTerminalResultFactory(), new DefaultFallbackResultFactory(), recorder,
                new DefaultRetryExecutor(duration -> {}, () -> 0.0), policies);
        var execution = orchestrator.execute(context);
        String actualTerminal = execution.status().name();
        boolean expectedSuccess = "SUCCESS".equals(expectedTerminal);
        boolean expectedDegraded = "DEGRADED".equals(expectedTerminal);
        boolean terminalPass = expectedSuccess ? execution.status() == WorkflowStatus.SUCCESS
                : expectedDegraded ? execution.status() == WorkflowStatus.DEGRADED
                : execution.status() == WorkflowStatus.FAILED;
        int repairs = (int) recorder.getEvents().stream()
                .filter(record -> record.type() == TraceEventType.REPAIR_APPLIED).count();
        int retries = (int) recorder.getEvents().stream()
                .filter(record -> record.type() == TraceEventType.RETRY_SCHEDULED).count();
        boolean actualFallback = execution.status() == WorkflowStatus.DEGRADED;
        boolean repairChanged = injectedFailure.equals("BOM_WHITESPACE") || injectedFailure.equals("OUTER_MARKDOWN_FENCE") || injectedFailure.equals("PARTIAL_STATUS_ALIAS");
        String expectedClassification = injectedFailure.startsWith("PROVIDER_") ? "RETRYABLE_PROVIDER"
                : injectedFailure.equals("TRANSPORT_TIMEOUT") ? "RETRYABLE_TRANSPORT"
                : repairChanged ? "SAFE_REPAIR"
                : injectedFailure.equals("TRUNCATED_JSON") || injectedFailure.equals("MALFORMED_JSON") ? "PARSE"
                : injectedFailure.equals("MISSING_REQUIRED_DECISION") ? "SCHEMA"
                : injectedFailure.equals("UNKNOWN_EVIDENCE_REF") ? "GROUNDING"
                : injectedFailure.equals("PERSISTENT_RETRYABLE_PROVIDER") ? "RETRYABLE_PROVIDER_EXHAUSTED"
                : "INTERNAL";
        String actualClassification = execution.status() == WorkflowStatus.DEGRADED ? "DEGRADED_FALLBACK"
                : execution.status() == WorkflowStatus.FAILED && context.lastError() != null ? context.lastError().category().name()
                : repairChanged ? "SAFE_REPAIR_SUCCESS"
                : retries > 0 ? "RETRY_SUCCESS" : "SUCCESS";
        boolean pass = terminalPass && (repairChanged ? repairs >= 1 : true)
                && (injectedFailure.startsWith("PROVIDER_") || injectedFailure.equals("TRANSPORT_TIMEOUT") || injectedFailure.equals("PERSISTENT_RETRYABLE_PROVIDER") ? retries >= (injectedFailure.equals("PERSISTENT_RETRYABLE_PROVIDER") ? 2 : 1) : retries == 0)
                && (injectedFailure.equals("PERSISTENT_RETRYABLE_PROVIDER") ? actualFallback : !actualFallback);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("caseId", id);
        row.put("injectedFailure", injectedFailure);
        row.put("expectedTerminal", expectedTerminal);
        row.put("actualTerminal", actualTerminal);
        row.put("expectedClassification", expectedClassification);
        row.put("actualClassification", actualClassification);
        row.put("providerCalls", index.get());
        row.put("attemptCount", context.attemptHistory().stream().filter(a -> a.stageType() == StageType.LLM_ANALYSIS).count());
        row.put("retryCount", retries);
        row.put("repairApplied", repairChanged);
        row.put("repairEventCount", repairs);
        row.put("fallbackSelected", actualFallback);
        row.put("resultOrigin", String.valueOf(execution.resultOrigin()));
        row.put("pass", pass);
        return row;
    }

    private ChatModelResponse response(boolean withEvidence) {
        return response(OfflineEvidenceFixtures.validJson(withEvidence));
    }

    private ChatModelResponse response(String raw) {
        return new ChatModelResponse(raw, "offline-request", "offline-model", "offline-v1", 10, 20, "stop");
    }
}
