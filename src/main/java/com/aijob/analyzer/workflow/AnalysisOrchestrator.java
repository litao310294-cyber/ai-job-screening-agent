package com.aijob.analyzer.workflow;

import com.aijob.analyzer.support.retry.OperationFailureException;
import com.aijob.analyzer.support.retry.RetryExecutor;
import com.aijob.analyzer.support.retry.RetryPolicy;
import com.aijob.analyzer.support.time.ExecutionDeadline;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceEventType;
import com.aijob.analyzer.trace.TraceRecorder;
import com.aijob.analyzer.workflow.domain.*;

import java.time.Instant;
import java.util.Map;

public final class AnalysisOrchestrator {
    private final Map<StageType, ? extends AnalysisStage> stages;
    private final TransitionPolicy transitionPolicy;
    private final RuleTerminalResultFactory ruleTerminalResultFactory;
    private final FallbackResultFactory fallbackResultFactory;
    private final FallbackPolicy fallbackPolicy;
    private final TraceRecorder traceRecorder;
    private final RetryExecutor retryExecutor;
    private final Map<StageType, RetryPolicy> retryPolicies;

    public AnalysisOrchestrator(Map<StageType, ? extends AnalysisStage> stages,
                               TransitionPolicy transitionPolicy,
                               RuleTerminalResultFactory ruleTerminalResultFactory,
                               FallbackResultFactory fallbackResultFactory,
                               TraceRecorder traceRecorder,
                               RetryExecutor retryExecutor,
                               Map<StageType, RetryPolicy> retryPolicies) {
        this(
                stages,
                transitionPolicy,
                ruleTerminalResultFactory,
                fallbackResultFactory,
                new DefaultFallbackPolicy(),
                traceRecorder,
                retryExecutor,
                retryPolicies
        );
    }

    public AnalysisOrchestrator(
            Map<StageType, ? extends AnalysisStage> stages,
            TransitionPolicy transitionPolicy,
            RuleTerminalResultFactory ruleTerminalResultFactory,
            FallbackResultFactory fallbackResultFactory,
            FallbackPolicy fallbackPolicy,
            TraceRecorder traceRecorder,
            RetryExecutor retryExecutor,
            Map<StageType, RetryPolicy> retryPolicies) {
        this.stages = Map.copyOf(stages);
        this.transitionPolicy = transitionPolicy;
        this.ruleTerminalResultFactory = ruleTerminalResultFactory;
        this.fallbackResultFactory = fallbackResultFactory;
        this.fallbackPolicy = fallbackPolicy;
        this.traceRecorder = traceRecorder;
        this.retryExecutor = retryExecutor;
        this.retryPolicies = Map.copyOf(retryPolicies);
    }

    public AnalysisExecution execute(AnalysisContext context) {
        TraceContext trace = new TraceContext(context.traceId(), context.taskId());
        context.setCurrentStage(StageType.RULE_CHECK);
        context.setWorkflowStatus(WorkflowStatus.RUNNING);
        ExecutionDeadline deadline = context.deadline();

        while (context.workflowStatus() == WorkflowStatus.RUNNING) {
            StageType currentType = context.currentStage();
            AnalysisStage stage = stages.get(currentType);

            if (stage == null) {
                context.setLastError(new WorkflowError("STAGE_NOT_FOUND", ErrorCategory.INTERNAL, false, "Stage " + currentType + " not implemented"));
                context.setStageStatus(currentType, StageExecutionStatus.FAILED);
                context.setWorkflowStatus(WorkflowStatus.FAILED);
                break;
            }

            StageResult result;
            try {
                RetryPolicy policy = retryPolicies.getOrDefault(currentType, defaultPolicy());
                result = retryExecutor.execute(
                        currentType.name(),
                        policy,
                        deadline,
                        trace,
                        traceRecorder,
                        attempt -> {
                            if (attempt > 1) {
                                safeRecord(() -> traceRecorder.event(trace, TraceEventType.RETRY_SCHEDULED,
                                    Map.of("stage", currentType, "attempt", attempt)));
                            }

                            Instant startTime = Instant.now();
                            context.setStageStatus(currentType, StageExecutionStatus.RUNNING);
                            safeRecord(() -> traceRecorder.stageStarted(trace, currentType, attempt));

                            try {
                                StageResult res = stage.execute(context);

                                Instant endTime = Instant.now();
                                context.addAttempt(new StageAttemptState(currentType, attempt,
                                    res.signal() == StageSignal.FAILED ? StageExecutionStatus.FAILED : StageExecutionStatus.SUCCESS,
                                    startTime, endTime));
                                context.setStageStatus(
                                        currentType,
                                        res.signal() == StageSignal.FAILED
                                                ? StageExecutionStatus.FAILED
                                                : StageExecutionStatus.SUCCESS
                                );

                                safeRecord(() -> traceRecorder.stageFinished(trace, currentType, attempt,
                                    res.signal() == StageSignal.FAILED ? StageExecutionStatus.FAILED : StageExecutionStatus.SUCCESS,
                                    java.time.Duration.between(startTime, endTime).toMillis(),
                                    res.error() == null ? null : res.error().code()));

                                if (res.signal() == StageSignal.FAILED) {
                                    throw new OperationFailureException(res.error());
                                }
                                return res;
                            } catch (OperationFailureException e) {
                                throw e;
                            } catch (Exception e) {
                                Instant endTime = Instant.now();
                                context.addAttempt(new StageAttemptState(currentType, attempt, StageExecutionStatus.FAILED, startTime, endTime));
                                context.setStageStatus(currentType, StageExecutionStatus.FAILED);
                                safeRecord(() -> traceRecorder.stageFinished(trace, currentType, attempt, StageExecutionStatus.FAILED,
                                    java.time.Duration.between(startTime, endTime).toMillis(), "INTERNAL_ERROR"));
                                throw e;
                            }
                        }
                );
            } catch (OperationFailureException e) {
                WorkflowError error = e.error();
                context.setStageStatus(currentType, StageExecutionStatus.FAILED);
                context.setLastError(error);
                if (fallbackPolicy.allows(currentType, error)) {
                    safeRecord(() -> traceRecorder.event(trace, TraceEventType.FALLBACK_SELECTED,
                        Map.of("stage", currentType, "error", error.code())));
                    context.setWorkflowStatus(WorkflowStatus.DEGRADED);
                    context.setResultOrigin(ResultOrigin.DEGRADED_FALLBACK);
                    context.setValidatedResult(fallbackResultFactory.fromRuleAndEvidence(context));
                    context.setJobDecision(context.validatedResult().decision());
                    break;
                } else {
                    context.setLastError(error);
                    context.setWorkflowStatus(WorkflowStatus.FAILED);
                    break;
                }
            } catch (Exception e) {
                // Rule WF-05: Unknown exceptions lead to Workflow FAILED
                context.setStageStatus(currentType, StageExecutionStatus.FAILED);
                context.setLastError(new WorkflowError("INTERNAL_ERROR", ErrorCategory.INTERNAL, false, e.getMessage()));
                context.setWorkflowStatus(WorkflowStatus.FAILED);
                break;
            }

            TransitionDecision decision = transitionPolicy.next(currentType, result, context);

            if (decision instanceof TransitionDecision.Continue cont) {
                safeRecord(() -> traceRecorder.event(trace, TraceEventType.TRANSITION,
                    Map.of("from", currentType, "to", cont.nextStage())));
                context.setCurrentStage(cont.nextStage());
            } else if (decision instanceof TransitionDecision.Terminal terminal) {
                context.setWorkflowStatus(terminal.status());
                if (currentType == StageType.RULE_CHECK && terminal.status() == WorkflowStatus.SUCCESS && context.jobDecision() == null) {
                    context.setResultOrigin(ResultOrigin.RULE_TERMINAL);
                    context.setValidatedResult(ruleTerminalResultFactory.from(context.ruleResult()));
                    context.setJobDecision(context.validatedResult().decision());
                } else if (terminal.status() == WorkflowStatus.SUCCESS) {
                    context.setResultOrigin(ResultOrigin.LLM_ANALYSIS);
                    if (context.validatedResult() != null) {
                        context.setJobDecision(context.validatedResult().decision());
                    }
                }
            } else if (decision instanceof TransitionDecision.InvokeFallback) {
                safeRecord(() -> traceRecorder.event(trace, TraceEventType.FALLBACK_SELECTED,
                    Map.of("stage", currentType)));
                context.setWorkflowStatus(WorkflowStatus.DEGRADED);
                context.setResultOrigin(ResultOrigin.DEGRADED_FALLBACK);
                context.setValidatedResult(fallbackResultFactory.fromRuleAndEvidence(context));
                context.setJobDecision(context.validatedResult().decision());
                break;
            }
        }

        markUnexecutedStagesSkipped(context);
        safeRecord(() -> traceRecorder.event(
                trace,
                TraceEventType.TRANSITION,
                Map.of(
                        "from", context.currentStage(),
                        "terminalStatus", context.workflowStatus().name()
                )
        ));

        return new AnalysisExecution(
                context.workflowStatus(),
                context.resultOrigin(),
                context.validatedResult(),
                context.lastError() != null ? context.lastError().safeMessage() : null
        );
    }

    private void markUnexecutedStagesSkipped(AnalysisContext context) {
        for (StageType stage : StageType.values()) {
            if (context.stageStatus(stage) == StageExecutionStatus.NOT_STARTED) {
                context.setStageStatus(stage, StageExecutionStatus.SKIPPED);
            }
        }
    }

    private void safeRecord(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            // Rule WF-08: Trace failure must not change business outcome
        }
    }

    private RetryPolicy defaultPolicy() {
        return new RetryPolicy(1, java.time.Duration.ZERO, 1.0, java.time.Duration.ZERO, 0.0);
    }
}
