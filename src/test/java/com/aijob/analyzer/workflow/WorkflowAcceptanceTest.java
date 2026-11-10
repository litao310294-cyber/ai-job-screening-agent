package com.aijob.analyzer.workflow;

import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.analysis.domain.JobDecision;
import com.aijob.analyzer.analysis.domain.RequirementAssessment;
import com.aijob.analyzer.analysis.domain.RequirementAssessmentStatus;
import com.aijob.analyzer.requirement.domain.AtomicRequirement;
import com.aijob.analyzer.requirement.domain.RequirementPriority;
import com.aijob.analyzer.requirement.domain.RequirementSet;
import com.aijob.analyzer.retrieval.domain.RetrievalResult;
import com.aijob.analyzer.support.FixtureFactory;
import com.aijob.analyzer.support.InMemoryTraceRecorder;
import com.aijob.analyzer.support.retry.DefaultRetryExecutor;
import com.aijob.analyzer.support.retry.RetryPolicy;
import com.aijob.analyzer.support.time.ExecutionDeadline;
import com.aijob.analyzer.support.time.TimeSource;
import com.aijob.analyzer.trace.TraceEventType;
import com.aijob.analyzer.workflow.domain.AnalysisExecution;
import com.aijob.analyzer.workflow.domain.ErrorCategory;
import com.aijob.analyzer.workflow.domain.ResultOrigin;
import com.aijob.analyzer.workflow.domain.RuleResult;
import com.aijob.analyzer.workflow.domain.RuleSignal;
import com.aijob.analyzer.workflow.domain.StageExecutionStatus;
import com.aijob.analyzer.workflow.domain.StageType;
import com.aijob.analyzer.workflow.domain.WorkflowError;
import com.aijob.analyzer.workflow.domain.WorkflowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowAcceptanceTest {

    private final List<StageType> order = new ArrayList<>();
    private final Map<StageType, AtomicInteger> counts = new EnumMap<>(StageType.class);
    private final Map<StageType, AnalysisStage> stages = new EnumMap<>(StageType.class);
    private InMemoryTraceRecorder traceRecorder;
    private AnalysisContext context;
    private AtomicInteger fallbackCount;
    private FallbackResultFactory fallbackResultFactory;

    @BeforeEach
    void setUp() {
        for (StageType stage : StageType.values()) {
            counts.put(stage, new AtomicInteger());
        }
        traceRecorder = new InMemoryTraceRecorder();
        fallbackCount = new AtomicInteger();
        fallbackResultFactory = ignored -> {
            fallbackCount.incrementAndGet();
            return insufficientEvidenceResult();
        };
        context = newContext();
    }

    @Test
    void ac_wf_01_normalPathLocksOrderCountsTerminalMetadataAndResult() {
        registerNormalStages();

        AnalysisExecution execution = orchestrator(defaultRetryPolicies()).execute(context);

        assertEquals(List.of(
                StageType.RULE_CHECK,
                StageType.PROFILE_RETRIEVAL,
                StageType.LLM_ANALYSIS,
                StageType.RESULT_VALIDATE,
                StageType.COMMIT_RESULT
        ), order);
        assertAllCounts(1, 1, 1, 1, 1);
        assertEquals(WorkflowStatus.SUCCESS, execution.status());
        assertEquals(ResultOrigin.LLM_ANALYSIS, execution.resultOrigin());
        assertEquals(FixtureFactory.analysisResult(), execution.result());
        assertEquals(StageType.COMMIT_RESULT, context.currentStage());
        assertEquals(JobDecision.RECOMMEND_APPLY, context.jobDecision());
        assertEquals(5, context.attemptHistory().size());
        assertTrue(context.attemptHistory().stream()
                .allMatch(attempt -> attempt.status() == StageExecutionStatus.SUCCESS));
        for (StageType stage : StageType.values()) {
            assertEquals(StageExecutionStatus.SUCCESS, context.stageStatus(stage));
        }
        assertTerminalTrace(WorkflowStatus.SUCCESS);
    }

    @Test
    void ac_wf_02_and_09_hardRejectSkipsEveryDownstreamStageAndBuildsFrozenResult() {
        var reasons = List.of("required internship days not met");
        register(StageType.RULE_CHECK, ctx -> {
            ctx.setRuleResult(new RuleResult(RuleSignal.HARD_REJECT, reasons));
            return StageResult.hardReject();
        });
        register(StageType.PROFILE_RETRIEVAL, ctx -> StageResult.success());
        register(StageType.LLM_ANALYSIS, ctx -> StageResult.success());
        register(StageType.RESULT_VALIDATE, ctx -> StageResult.success());
        register(StageType.COMMIT_RESULT, ctx -> StageResult.success());

        AnalysisExecution execution = orchestrator(defaultRetryPolicies()).execute(context);

        assertAllCounts(1, 0, 0, 0, 0);
        assertEquals(WorkflowStatus.SUCCESS, execution.status());
        assertEquals(ResultOrigin.RULE_TERMINAL, execution.resultOrigin());
        assertEquals(JobDecision.NOT_RECOMMEND, context.jobDecision());
        assertEquals(JobDecision.NOT_RECOMMEND, execution.result().decision());
        assertEquals("deterministic hard constraint not satisfied", execution.result().overallAssessment());
        assertEquals(List.of(), execution.result().requirementAssessments());
        assertEquals(reasons, execution.result().risks());
        assertEquals(List.of(), execution.result().strengths());
        assertEquals("do not continue semantic matching for this execution", execution.result().recommendation());
        assertEquals("Workflow terminated early due to rule violation", execution.result().summary());
        assertEquals(StageExecutionStatus.SUCCESS, context.stageStatus(StageType.RULE_CHECK));
        assertEquals(StageExecutionStatus.SKIPPED, context.stageStatus(StageType.PROFILE_RETRIEVAL));
        assertEquals(StageExecutionStatus.SKIPPED, context.stageStatus(StageType.LLM_ANALYSIS));
        assertEquals(StageExecutionStatus.SKIPPED, context.stageStatus(StageType.RESULT_VALIDATE));
        assertEquals(StageExecutionStatus.SKIPPED, context.stageStatus(StageType.COMMIT_RESULT));
        assertTerminalTrace(WorkflowStatus.SUCCESS);
    }

    @Test
    void ac_wf_03_failedStageResultEntersRetryWithoutRerunningSuccessfulStages() {
        register(StageType.RULE_CHECK, continuingRule());
        register(StageType.PROFILE_RETRIEVAL, ctx -> StageResult.success());
        register(StageType.LLM_ANALYSIS, ctx -> {
            if (counts.get(StageType.LLM_ANALYSIS).get() == 1) {
                return StageResult.failed(new WorkflowError(
                        "LLM_TIMEOUT",
                        ErrorCategory.PROVIDER,
                        true,
                        "timeout"
                ));
            }
            ctx.setRawResult(FixtureFactory.analysisResult());
            return StageResult.success();
        });
        register(StageType.RESULT_VALIDATE, validateRawResult());
        register(StageType.COMMIT_RESULT, ctx -> StageResult.success());

        AnalysisExecution execution = orchestrator(defaultRetryPolicies()).execute(context);

        assertEquals(WorkflowStatus.SUCCESS, execution.status());
        assertAllCounts(1, 1, 2, 1, 1);
        assertTrue(traceRecorder.getEvents().stream().anyMatch(event ->
                event.type() == TraceEventType.RETRY_SCHEDULED
                        && event.attributes().get("stage") == StageType.LLM_ANALYSIS
                        && event.attributes().get("attempt").equals(2)));
        assertTrue(traceRecorder.getStageFinishes().stream().anyMatch(event ->
                event.stage() == StageType.LLM_ANALYSIS
                        && event.attempt() == 1
                        && event.status() == StageExecutionStatus.FAILED
                        && "LLM_TIMEOUT".equals(event.errorCode())));
    }

    @Test
    void ac_wf_04_exhaustedKnownLlmFailureUsesDeterministicFallbackOnlyOnce() {
        register(StageType.RULE_CHECK, continuingRule());
        register(StageType.PROFILE_RETRIEVAL, ctx -> StageResult.success());
        register(StageType.LLM_ANALYSIS, ctx -> StageResult.failed(new WorkflowError(
                "LLM_TIMEOUT",
                ErrorCategory.PROVIDER,
                true,
                "Final analysis unavailable"
        )));
        register(StageType.RESULT_VALIDATE, ctx -> StageResult.success());
        register(StageType.COMMIT_RESULT, ctx -> StageResult.success());

        AnalysisExecution execution = orchestrator(defaultRetryPolicies()).execute(context);

        assertEquals(WorkflowStatus.DEGRADED, execution.status());
        assertEquals(ResultOrigin.DEGRADED_FALLBACK, execution.resultOrigin());
        assertEquals(JobDecision.INSUFFICIENT_EVIDENCE, execution.result().decision());
        assertEquals("Final analysis unavailable", execution.degradedReason());
        assertAllCounts(1, 1, 3, 0, 0);
        assertEquals(1, fallbackCount.get());
        assertEquals(StageExecutionStatus.FAILED, context.stageStatus(StageType.LLM_ANALYSIS));
        assertEquals(StageExecutionStatus.SKIPPED, context.stageStatus(StageType.RESULT_VALIDATE));
        assertEquals(StageExecutionStatus.SKIPPED, context.stageStatus(StageType.COMMIT_RESULT));
        assertTrue(traceRecorder.getEvents().stream()
                .anyMatch(event -> event.type() == TraceEventType.FALLBACK_SELECTED));
        assertTerminalTrace(WorkflowStatus.DEGRADED);
    }

    @Test
    void ac_wf_04_persistenceErrorAtLlmBoundaryIsNotEligibleForFallback() {
        register(StageType.RULE_CHECK, continuingRule());
        register(StageType.PROFILE_RETRIEVAL, ctx -> StageResult.success());
        register(StageType.LLM_ANALYSIS, ctx -> StageResult.failed(new WorkflowError(
                "WRONG_BOUNDARY",
                ErrorCategory.PERSISTENCE,
                false,
                "persistence failure"
        )));
        register(StageType.RESULT_VALIDATE, ctx -> StageResult.success());
        register(StageType.COMMIT_RESULT, ctx -> StageResult.success());

        AnalysisExecution execution = orchestrator(defaultRetryPolicies()).execute(context);

        assertEquals(WorkflowStatus.FAILED, execution.status());
        assertNull(execution.result());
        assertEquals(0, fallbackCount.get());
        assertAllCounts(1, 1, 1, 0, 0);
        assertEquals(StageExecutionStatus.FAILED, context.stageStatus(StageType.LLM_ANALYSIS));
        assertEquals(StageExecutionStatus.SKIPPED, context.stageStatus(StageType.RESULT_VALIDATE));
        assertEquals(StageExecutionStatus.SKIPPED, context.stageStatus(StageType.COMMIT_RESULT));
        assertTerminalTrace(WorkflowStatus.FAILED);
    }

    @Test
    void ac_wf_06_unknownRuntimeBugFailsWithoutFallbackOrDownstreamExecution() {
        register(StageType.RULE_CHECK, continuingRule());
        register(StageType.PROFILE_RETRIEVAL, ctx -> StageResult.success());
        register(StageType.LLM_ANALYSIS, ctx -> {
            throw new RuntimeException("bug");
        });
        register(StageType.RESULT_VALIDATE, ctx -> StageResult.success());
        register(StageType.COMMIT_RESULT, ctx -> StageResult.success());

        AnalysisExecution execution = orchestrator(defaultRetryPolicies()).execute(context);

        assertEquals(WorkflowStatus.FAILED, execution.status());
        assertEquals(ErrorCategory.INTERNAL, context.lastError().category());
        assertEquals(0, fallbackCount.get());
        assertAllCounts(1, 1, 1, 0, 0);
        assertTerminalTrace(WorkflowStatus.FAILED);
    }

    @Test
    void ac_wf_08_noEvidenceContinuesWithEmptyEvidenceAndAllowsInsufficientResult() {
        var requirement = new AtomicRequirement(
                "R0001",
                "skill",
                "Java",
                RequirementPriority.REQUIRED,
                "Java required"
        );
        var requirementSet = new RequirementSet(
                1L,
                FixtureFactory.requirementIdentity(),
                List.of(),
                List.of(requirement),
                List.of()
        );
        register(StageType.RULE_CHECK, continuingRule());
        register(StageType.PROFILE_RETRIEVAL, ctx -> {
            ctx.setRequirementSet(requirementSet);
            ctx.setRetrievalResult(new RetrievalResult(Map.of("R0001", List.of())));
            return StageResult.noEvidence();
        });
        register(StageType.LLM_ANALYSIS, ctx -> {
            assertNotNull(ctx.requirementSet());
            assertEquals(List.of(), ctx.retrievalResult().evidenceByRequirement().get("R0001"));
            ctx.setRawResult(insufficientEvidenceResult());
            return StageResult.success();
        });
        register(StageType.RESULT_VALIDATE, validateRawResult());
        register(StageType.COMMIT_RESULT, ctx -> StageResult.success());

        AnalysisExecution execution = orchestrator(defaultRetryPolicies()).execute(context);

        assertEquals(WorkflowStatus.SUCCESS, execution.status());
        assertEquals(JobDecision.INSUFFICIENT_EVIDENCE, execution.result().decision());
        assertFalse(execution.result().requirementAssessments().isEmpty());
        assertEquals(RequirementAssessmentStatus.INSUFFICIENT_EVIDENCE,
                execution.result().requirementAssessments().getFirst().status());
        assertAllCounts(1, 1, 1, 1, 1);
    }

    @Test
    void workflowContractUsesFrozenFallbackFactoryMethodName() throws Exception {
        assertEquals(JobAnalysisResult.class,
                FallbackResultFactory.class
                        .getMethod("fromRuleAndEvidence", AnalysisContext.class)
                        .getReturnType());
    }

    private void registerNormalStages() {
        register(StageType.RULE_CHECK, continuingRule());
        register(StageType.PROFILE_RETRIEVAL, ctx -> StageResult.success());
        register(StageType.LLM_ANALYSIS, ctx -> {
            ctx.setRawResult(FixtureFactory.analysisResult());
            return StageResult.success();
        });
        register(StageType.RESULT_VALIDATE, validateRawResult());
        register(StageType.COMMIT_RESULT, ctx -> StageResult.success());
    }

    private Function<AnalysisContext, StageResult> continuingRule() {
        return ctx -> {
            ctx.setRuleResult(new RuleResult(RuleSignal.CONTINUE, List.of()));
            return StageResult.success();
        };
    }

    private Function<AnalysisContext, StageResult> validateRawResult() {
        return ctx -> {
            ctx.setValidatedResult(ctx.rawResult());
            return StageResult.success();
        };
    }

    private void register(StageType type, Function<AnalysisContext, StageResult> behavior) {
        stages.put(type, new AnalysisStage() {
            @Override
            public StageType type() {
                return type;
            }

            @Override
            public StageResult execute(AnalysisContext context) {
                counts.get(type).incrementAndGet();
                order.add(type);
                return behavior.apply(context);
            }
        });
    }

    private AnalysisOrchestrator orchestrator(Map<StageType, RetryPolicy> retryPolicies) {
        return new AnalysisOrchestrator(
                stages,
                new DefaultTransitionPolicy(),
                new DefaultRuleTerminalResultFactory(),
                fallbackResultFactory,
                traceRecorder,
                new DefaultRetryExecutor(duration -> { }, () -> 0.5),
                retryPolicies
        );
    }

    private Map<StageType, RetryPolicy> defaultRetryPolicies() {
        return Map.of(
                StageType.LLM_ANALYSIS,
                new RetryPolicy(3, Duration.ZERO, 1.0, Duration.ZERO, 0.0)
        );
    }

    private AnalysisContext newContext() {
        var profile = FixtureFactory.candidateProfile(1L, "user-1");
        var job = new JobContext(
                "platform",
                "job-1",
                "Java Backend Intern",
                "Shanghai",
                3,
                "3 months",
                "salary",
                "description",
                FixtureFactory.jobFingerprint()
        );
        return new AnalysisContext(
                "trace-1",
                "task-1",
                job,
                profile,
                profile.identity(),
                FixtureFactory.analysisIdentity(),
                ExecutionDeadline.fromNow(Duration.ofSeconds(60), TimeSource.SYSTEM),
                FixtureFactory.analysisDependencySnapshot()
        );
    }

    private JobAnalysisResult insufficientEvidenceResult() {
        return new JobAnalysisResult(
                JobDecision.INSUFFICIENT_EVIDENCE,
                "Semantic analysis unavailable or evidence insufficient",
                List.of(new RequirementAssessment(
                        "R0001",
                        RequirementAssessmentStatus.INSUFFICIENT_EVIDENCE,
                        List.of(),
                        "Semantic judgment unavailable",
                        "Evidence is insufficient"
                )),
                List.of(),
                List.of(),
                "Review the requirement manually",
                "No unsupported conclusion was generated"
        );
    }

    private void assertAllCounts(int rule, int retrieval, int llm, int validate, int commit) {
        assertEquals(rule, counts.get(StageType.RULE_CHECK).get());
        assertEquals(retrieval, counts.get(StageType.PROFILE_RETRIEVAL).get());
        assertEquals(llm, counts.get(StageType.LLM_ANALYSIS).get());
        assertEquals(validate, counts.get(StageType.RESULT_VALIDATE).get());
        assertEquals(commit, counts.get(StageType.COMMIT_RESULT).get());
    }

    private void assertTerminalTrace(WorkflowStatus status) {
        assertTrue(traceRecorder.getEvents().stream().anyMatch(event ->
                event.type() == TraceEventType.TRANSITION
                        && status.name().equals(event.attributes().get("terminalStatus"))));
    }
}
