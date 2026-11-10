package com.aijob.analyzer.workflow;

import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.analysis.domain.JobDecision;
import com.aijob.analyzer.support.FixtureFactory;
import com.aijob.analyzer.support.InMemoryTraceRecorder;
import com.aijob.analyzer.support.retry.DefaultRetryExecutor;
import com.aijob.analyzer.support.retry.OperationFailureException;
import com.aijob.analyzer.support.retry.RetryPolicy;
import com.aijob.analyzer.support.time.ExecutionDeadline;
import com.aijob.analyzer.support.time.TimeSource;
import com.aijob.analyzer.trace.TraceRecorder;
import com.aijob.analyzer.workflow.domain.*;
import com.aijob.analyzer.workflow.support.CountingStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AnalysisOrchestratorTest {

    private Map<StageType, CountingStage> stages;
    private TransitionPolicy transitionPolicy;
    private RuleTerminalResultFactory ruleTerminalResultFactory;
    private FallbackResultFactory fallbackResultFactory;
    private TraceRecorder traceRecorder;
    private AnalysisOrchestrator orchestrator;
    private AnalysisContext context;

    @BeforeEach
    void setUp() {
        stages = new HashMap<>();
        transitionPolicy = new DefaultTransitionPolicy();
        ruleTerminalResultFactory = new DefaultRuleTerminalResultFactory();
        fallbackResultFactory = ctx -> FixtureFactory.analysisResult();
        traceRecorder = new InMemoryTraceRecorder();

        var profile = FixtureFactory.candidateProfile(1L, "user-1");
        var jobCtx = new JobContext("platform", "job-1", "title", "city", 3, "3 months", "salary", "desc", FixtureFactory.jobFingerprint());
        context = new AnalysisContext(
                "trace-1",
                "task-1",
                jobCtx,
                profile,
                profile.identity(),
                FixtureFactory.analysisIdentity(),
                ExecutionDeadline.fromNow(Duration.ofSeconds(60), TimeSource.SYSTEM),
                FixtureFactory.analysisDependencySnapshot()
        );
    }

    private void registerStage(StageType type, StageSignal signal) {
        stages.put(type, new CountingStage(type, ctx -> {
            if (type == StageType.RULE_CHECK) {
                RuleSignal ruleSignal = signal == StageSignal.HARD_REJECT ? RuleSignal.HARD_REJECT : RuleSignal.CONTINUE;
                ctx.setRuleResult(new RuleResult(ruleSignal, signal == StageSignal.HARD_REJECT ? List.of("rejected") : List.of()));
            }
            if (type == StageType.COMMIT_RESULT && signal == StageSignal.SUCCESS) {
                ctx.setValidatedResult(FixtureFactory.analysisResult());
            }
            if (signal == StageSignal.FAILED) {
                throw new OperationFailureException(new WorkflowError("ERR", ErrorCategory.PROVIDER, true, "retryable"));
            }
            return new StageResult(signal, null);
        }));
    }

    private AnalysisOrchestrator createOrchestrator() {
        return createOrchestrator(new HashMap<>());
    }

    private AnalysisOrchestrator createOrchestrator(Map<StageType, RetryPolicy> policies) {
        var retryExecutor = new DefaultRetryExecutor(d -> {}, () -> 0.5);
        return new AnalysisOrchestrator(stages, transitionPolicy, ruleTerminalResultFactory, fallbackResultFactory, traceRecorder, retryExecutor, policies);
    }

    @Test
    void normalPathExecutionOrder() {
        registerStage(StageType.RULE_CHECK, StageSignal.SUCCESS);
        registerStage(StageType.PROFILE_RETRIEVAL, StageSignal.SUCCESS);
        registerStage(StageType.LLM_ANALYSIS, StageSignal.SUCCESS);
        registerStage(StageType.RESULT_VALIDATE, StageSignal.SUCCESS);
        registerStage(StageType.COMMIT_RESULT, StageSignal.SUCCESS);

        orchestrator = createOrchestrator();
        var execution = orchestrator.execute(context);

        assertEquals(WorkflowStatus.SUCCESS, execution.status());
        assertEquals(1, stages.get(StageType.RULE_CHECK).count());
        assertEquals(1, stages.get(StageType.PROFILE_RETRIEVAL).count());
        assertEquals(1, stages.get(StageType.LLM_ANALYSIS).count());
        assertEquals(1, stages.get(StageType.RESULT_VALIDATE).count());
        assertEquals(1, stages.get(StageType.COMMIT_RESULT).count());
    }

    @Test
    void hardRejectEarlyTermination() {
        registerStage(StageType.RULE_CHECK, StageSignal.HARD_REJECT);
        registerStage(StageType.PROFILE_RETRIEVAL, StageSignal.SUCCESS);
        registerStage(StageType.LLM_ANALYSIS, StageSignal.SUCCESS);

        orchestrator = createOrchestrator();
        var execution = orchestrator.execute(context);

        assertEquals(WorkflowStatus.SUCCESS, execution.status());
        assertEquals(ResultOrigin.RULE_TERMINAL, execution.resultOrigin());
        assertEquals(JobDecision.NOT_RECOMMEND, execution.result().decision());

        assertEquals(1, stages.get(StageType.RULE_CHECK).count());
        assertEquals(0, stages.get(StageType.PROFILE_RETRIEVAL).count());
        assertEquals(0, stages.get(StageType.LLM_ANALYSIS).count());
    }

    @Test
    void retrySuccessOnTransientFailure() {
        var attempts = new HashMap<StageType, Integer>();
        stages.put(StageType.LLM_ANALYSIS, new CountingStage(StageType.LLM_ANALYSIS, ctx -> {
            int count = attempts.merge(StageType.LLM_ANALYSIS, 1, Integer::sum);
            if (count < 2) {
                throw new OperationFailureException(new WorkflowError("RETRY", ErrorCategory.PROVIDER, true, "retry"));
            }
            return StageResult.success();
        }));

        registerStage(StageType.RULE_CHECK, StageSignal.SUCCESS);
        registerStage(StageType.PROFILE_RETRIEVAL, StageSignal.SUCCESS);
        registerStage(StageType.RESULT_VALIDATE, StageSignal.SUCCESS);
        registerStage(StageType.COMMIT_RESULT, StageSignal.SUCCESS);

        Map<StageType, RetryPolicy> policies = new HashMap<>();
        policies.put(StageType.LLM_ANALYSIS, new RetryPolicy(3, Duration.ZERO, 1.0, Duration.ZERO, 0.0));

        orchestrator = createOrchestrator(policies);
        var execution = orchestrator.execute(context);

        assertEquals(WorkflowStatus.SUCCESS, execution.status());
        assertEquals(2, stages.get(StageType.LLM_ANALYSIS).count());
    }

    @Test
    void noEvidenceContinuesToLlm() {
        registerStage(StageType.RULE_CHECK, StageSignal.SUCCESS);
        registerStage(StageType.PROFILE_RETRIEVAL, StageSignal.NO_EVIDENCE);
        registerStage(StageType.LLM_ANALYSIS, StageSignal.SUCCESS);
        registerStage(StageType.RESULT_VALIDATE, StageSignal.SUCCESS);
        registerStage(StageType.COMMIT_RESULT, StageSignal.SUCCESS);

        orchestrator = createOrchestrator();
        var execution = orchestrator.execute(context);

        assertEquals(WorkflowStatus.SUCCESS, execution.status());
        assertEquals(1, stages.get(StageType.PROFILE_RETRIEVAL).count());
        assertEquals(1, stages.get(StageType.LLM_ANALYSIS).count());
    }

    @Test
    void traceFailureDoesNotBreakWorkflow() {
        registerStage(StageType.RULE_CHECK, StageSignal.SUCCESS);
        registerStage(StageType.PROFILE_RETRIEVAL, StageSignal.SUCCESS);
        registerStage(StageType.LLM_ANALYSIS, StageSignal.SUCCESS);
        registerStage(StageType.RESULT_VALIDATE, StageSignal.SUCCESS);
        registerStage(StageType.COMMIT_RESULT, StageSignal.SUCCESS);

        traceRecorder = new TraceRecorder() {
            @Override
            public void stageStarted(com.aijob.analyzer.trace.TraceContext trace, StageType stage, int attempt) {
                throw new RuntimeException("trace failed");
            }
            @Override
            public void stageFinished(com.aijob.analyzer.trace.TraceContext trace, StageType stage, int attempt, StageExecutionStatus status, long durationMillis, String errorCode) {}
            @Override
            public void event(com.aijob.analyzer.trace.TraceContext trace, com.aijob.analyzer.trace.TraceEventType type, Map<String, Object> attributes) {}
        };

        orchestrator = createOrchestrator();
        var execution = orchestrator.execute(context);

        assertEquals(WorkflowStatus.SUCCESS, execution.status());
    }

    @Test
    void internalBugResultsInWorkflowFailed() {
        registerStage(StageType.RULE_CHECK, StageSignal.SUCCESS);
        stages.put(StageType.PROFILE_RETRIEVAL, new CountingStage(StageType.PROFILE_RETRIEVAL, ctx -> {
            throw new RuntimeException("internal bug");
        }));

        orchestrator = createOrchestrator();
        var execution = orchestrator.execute(context);

        assertEquals(WorkflowStatus.FAILED, execution.status());
        assertEquals(ErrorCategory.INTERNAL, context.lastError().category());
    }
}
