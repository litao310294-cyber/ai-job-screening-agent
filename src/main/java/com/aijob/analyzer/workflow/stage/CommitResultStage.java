package com.aijob.analyzer.workflow.stage;

import com.aijob.analyzer.analysis.CanonicalCommitResult;
import com.aijob.analyzer.analysis.identity.AnalysisIdentity;
import com.aijob.analyzer.analysis.port.AnalysisResultRepository;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceEventType;
import com.aijob.analyzer.trace.TraceRecorder;
import com.aijob.analyzer.workflow.AnalysisContext;
import com.aijob.analyzer.workflow.AnalysisStage;
import com.aijob.analyzer.workflow.StageResult;
import com.aijob.analyzer.workflow.domain.ErrorCategory;
import com.aijob.analyzer.workflow.domain.StageType;
import com.aijob.analyzer.workflow.domain.WorkflowError;

import java.util.Map;
import java.util.Objects;

public class CommitResultStage implements AnalysisStage {
    private final AnalysisResultRepository repository;
    private final TraceRecorder traceRecorder;

    public CommitResultStage(AnalysisResultRepository repository, TraceRecorder traceRecorder) {
        this.repository = Objects.requireNonNull(repository);
        this.traceRecorder = Objects.requireNonNull(traceRecorder);
    }

    @Override
    public StageType type() {
        return StageType.COMMIT_RESULT;
    }

    @Override
    public StageResult execute(AnalysisContext context) {
        AnalysisIdentity identity = context.analysisIdentity();

        // Construct the full final snapshot for persistence
        var initial = context.dependencySnapshot();
        var finalSnapshot = new com.aijob.analyzer.analysis.identity.AnalysisDependencySnapshot(
                initial.jobFingerprint(),
                initial.profileArtifactId(),
                initial.profileArtifactIdentity(),
                context.requirementSet() != null ? context.requirementSet().artifactId() : 0L,
                initial.requirementSetIdentity(),
                initial.ruleVersion(),
                initial.retrievalSemanticFingerprint(),
                initial.retrievalSemanticSnapshot(),
                initial.finalLlmSemanticFingerprint(),
                initial.finalLlmSemanticSnapshot()
        );

        CanonicalCommitResult commitResult = repository.commitCanonical(
                context.analysisIdentity(),
                context.validatedResult(),
                finalSnapshot,
                context.traceContext(),
                context.traceRecorder()
        );

        if (commitResult instanceof CanonicalCommitResult.Inserted inserted) {
            context.setValidatedResult(inserted.artifact().result());
            return StageResult.success();
        } else if (commitResult instanceof CanonicalCommitResult.Reused reused) {
            context.setValidatedResult(reused.artifact().result());
            safeEvent(context, TraceEventType.CANONICAL_REUSE, Map.of(
                    "identity", identity,
                    "artifactId", reused.artifact().artifactId()
            ));
            return StageResult.success();
        } else if (commitResult instanceof CanonicalCommitResult.Uncertain uncertain) {
            safeEvent(context, TraceEventType.READBACK_REQUIRED, Map.of(
                    "errorCode", uncertain.errorCode(),
                    "identity", identity
            ));

            var readBack = repository.readBack(identity);
            if (readBack.outcome() == DbReadOutcome.FOUND) {
                context.setValidatedResult(readBack.value().result());
                return StageResult.success();
            }

            if (readBack.outcome() == DbReadOutcome.NOT_FOUND) {
                return StageResult.failed(new WorkflowError(
                        "COMMIT_UNCERTAIN_READBACK_NOT_FOUND",
                        ErrorCategory.PERSISTENCE,
                        false,
                        "Commit outcome remains uncertain after read-back found no artifact"
                ));
            }

            return StageResult.failed(new WorkflowError(
                "COMMIT_UNCERTAIN_READBACK_FAILED",
                    ErrorCategory.PERSISTENCE,
                    false,
                    "Commit outcome remains uncertain because read-back failed: " + readBack.errorCode()
            ));
        }

        return StageResult.failed(new WorkflowError(
                "UNKNOWN_COMMIT_RESULT",
                ErrorCategory.INTERNAL,
                false,
                "Unknown commit result type"
        ));
    }

    private void safeEvent(
            AnalysisContext context,
            TraceEventType eventType,
            Map<String, Object> attributes) {
        try {
            traceRecorder.event(
                    new TraceContext(context.traceId(), context.taskId()),
                    eventType,
                    attributes
            );
        } catch (RuntimeException ignored) {
            // Trace failure is an observability gap, never a business failure.
        }
    }
}
