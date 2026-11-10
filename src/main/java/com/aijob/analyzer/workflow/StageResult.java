package com.aijob.analyzer.workflow;

import com.aijob.analyzer.workflow.domain.StageSignal;
import com.aijob.analyzer.workflow.domain.WorkflowError;

import java.util.Objects;

public record StageResult(
        StageSignal signal,
        WorkflowError error) {

    public StageResult {
        Objects.requireNonNull(signal);
        if (signal == StageSignal.FAILED && error == null) {
            throw new IllegalArgumentException("FAILED result must carry a WorkflowError");
        }
        if (signal != StageSignal.FAILED && error != null) {
            throw new IllegalArgumentException("Non-FAILED result must not carry a WorkflowError");
        }
    }

    public static StageResult success() {
        return new StageResult(StageSignal.SUCCESS, null);
    }

    public static StageResult hardReject() {
        return new StageResult(StageSignal.HARD_REJECT, null);
    }

    public static StageResult noEvidence() {
        return new StageResult(StageSignal.NO_EVIDENCE, null);
    }

    public static StageResult failed(WorkflowError error) {
        return new StageResult(StageSignal.FAILED, error);
    }
}
