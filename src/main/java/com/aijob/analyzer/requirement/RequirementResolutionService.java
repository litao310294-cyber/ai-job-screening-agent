package com.aijob.analyzer.requirement;

import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.analysis.identity.RequirementIdentity;
import com.aijob.analyzer.llm.LlmExecutionContext;
import com.aijob.analyzer.llm.LlmSemanticSnapshot;
import com.aijob.analyzer.llm.RequirementParserService;
import com.aijob.analyzer.llm.recovery.LlmRecoveryPolicy;
import com.aijob.analyzer.llm.recovery.LlmServiceRole;
import com.aijob.analyzer.llm.recovery.RecoveryAction;
import com.aijob.analyzer.requirement.domain.RequirementSet;
import com.aijob.analyzer.requirement.domain.ValidatedRequirementPayload;
import com.aijob.analyzer.requirement.port.RequirementSetRepository;
import com.aijob.analyzer.support.retry.OperationFailureException;
import com.aijob.analyzer.support.retry.RetryExecutor;
import com.aijob.analyzer.support.retry.RetryPolicy;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceRecorder;
import com.aijob.analyzer.workflow.domain.ErrorCategory;
import com.aijob.analyzer.workflow.domain.WorkflowError;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RequirementResolutionService {

    private final RequirementSetRepository repository;
    private final RequirementParserService parserService;
    private final RetryExecutor retryExecutor;
    private final LlmRecoveryPolicy recoveryPolicy;

    public RequirementResolutionService(
            RequirementSetRepository repository,
            RequirementParserService parserService,
            RetryExecutor retryExecutor,
            LlmRecoveryPolicy recoveryPolicy) {
        this.repository = repository;
        this.parserService = parserService;
        this.retryExecutor = retryExecutor;
        this.recoveryPolicy = recoveryPolicy;
    }

    public RequirementSet resolve(JobContext job, RequirementIdentity identity, LlmSemanticSnapshot snapshot, TraceContext trace, TraceRecorder recorder) {
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(100), 2.0, Duration.ofSeconds(1), 0.1);

        ValidatedRequirementPayload payload = retryExecutor.execute(
            "RequirementResolution",
            policy,
            null,
            trace,
            recorder,
            attempt -> {
                try {
                    LlmExecutionContext context = new LlmExecutionContext(snapshot, attempt, 0);
                    return parserService.parseAndValidate(job, identity, context, trace, recorder);
                } catch (Exception e) {
                    RecoveryAction action = recoveryPolicy.decide(LlmServiceRole.REQUIREMENT_PARSER, e, attempt, 0);
                    throw new OperationFailureException(new WorkflowError(
                        "REQUIREMENT_PARSE_FAILED",
                        ErrorCategory.PARSE,
                        action == RecoveryAction.RETRY,
                        e.getMessage()
                    ));
                }
            }
        );

        return repository.insertCanonical(payload);
    }
}
