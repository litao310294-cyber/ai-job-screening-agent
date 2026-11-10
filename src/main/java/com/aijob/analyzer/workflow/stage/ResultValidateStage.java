package com.aijob.analyzer.workflow.stage;

import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceEventType;
import com.aijob.analyzer.trace.TraceRecorder;
import com.aijob.analyzer.workflow.AnalysisContext;
import com.aijob.analyzer.workflow.AnalysisStage;
import com.aijob.analyzer.workflow.StageResult;
import com.aijob.analyzer.workflow.domain.StageType;
import com.aijob.analyzer.workflow.domain.WorkflowError;
import com.aijob.analyzer.workflow.port.ResultValidator;
import com.aijob.analyzer.workflow.port.SafeResultRepair;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ResultValidateStage implements AnalysisStage {
    private final TraceRecorder traceRecorder;
    private final ResultValidator validator;
    private final SafeResultRepair repair;

    public ResultValidateStage(
            TraceRecorder traceRecorder,
            ResultValidator validator,
            SafeResultRepair repair) {
        this.traceRecorder = Objects.requireNonNull(traceRecorder, "traceRecorder");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.repair = Objects.requireNonNull(repair, "repair");
    }

    @Override
    public StageType type() {
        return StageType.RESULT_VALIDATE;
    }

    @Override
    public StageResult execute(AnalysisContext context) {
        JobAnalysisResult candidate = context.rawResult();
        Optional<WorkflowError> firstValidation = validator.validate(candidate, context);
        if (firstValidation.isEmpty()) {
            context.setValidatedResult(candidate);
            return StageResult.success();
        }

        WorkflowError validationError = firstValidation.orElseThrow();
        Optional<JobAnalysisResult> repaired = repair.repair(candidate, validationError, context);
        if (repaired.isEmpty()) {
            return StageResult.failed(validationError);
        }

        recordRepair(context, validationError);
        JobAnalysisResult repairedResult = repaired.orElseThrow();
        Optional<WorkflowError> secondValidation = validator.validate(repairedResult, context);
        if (secondValidation.isPresent()) {
            return StageResult.failed(secondValidation.orElseThrow());
        }

        context.setValidatedResult(repairedResult);
        return StageResult.success();
    }

    private void recordRepair(AnalysisContext context, WorkflowError validationError) {
        try {
            traceRecorder.event(
                    new TraceContext(context.traceId(), context.taskId()),
                    TraceEventType.REPAIR_APPLIED,
                    Map.of(
                            "stage", StageType.RESULT_VALIDATE,
                            "validationError", validationError.code()
                    )
            );
        } catch (RuntimeException ignored) {
            // Trace failure is isolated from validation and repair semantics.
        }
    }
}
