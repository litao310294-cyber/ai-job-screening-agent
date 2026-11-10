package com.aijob.analyzer.workflow;

import com.aijob.analyzer.analysis.CanonicalCommitResult;
import com.aijob.analyzer.analysis.domain.AnalysisArtifact;
import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.analysis.port.AnalysisResultRepository;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.persistence.model.DbReadResult;
import com.aijob.analyzer.support.FixtureFactory;
import com.aijob.analyzer.support.InMemoryTraceRecorder;
import com.aijob.analyzer.support.time.ExecutionDeadline;
import com.aijob.analyzer.support.time.TimeSource;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceEventType;
import com.aijob.analyzer.trace.TraceRecorder;
import com.aijob.analyzer.workflow.domain.ErrorCategory;
import com.aijob.analyzer.workflow.domain.StageExecutionStatus;
import com.aijob.analyzer.workflow.domain.StageSignal;
import com.aijob.analyzer.workflow.domain.StageType;
import com.aijob.analyzer.workflow.stage.CommitResultStage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommitResultStageTest {

    @Test
    void insertedAndReusedUseTheFrozenIdentityAndDurableArtifactResult() {
        var repository = mock(AnalysisResultRepository.class);
        var recorder = new InMemoryTraceRecorder();
        var context = context();
        var inserted = artifact(11L, FixtureFactory.analysisResult());
        when(repository.commitCanonical(
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(new CanonicalCommitResult.Inserted(inserted));

        var insertedResult = new CommitResultStage(repository, recorder).execute(context);

        assertEquals(StageSignal.SUCCESS, insertedResult.signal());
        assertEquals(inserted.result(), context.validatedResult());
        verify(repository).commitCanonical(
                any(),
                any(),
                any(),
                any(),
                any()
        );

        var reusedResultBody = new com.aijob.analyzer.analysis.domain.JobAnalysisResult(
                com.aijob.analyzer.analysis.domain.JobDecision.CONSIDER,
                "canonical",
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                "review",
                "reused"
        );
        var reused = artifact(12L, reusedResultBody);
        when(repository.commitCanonical(any(), any(), any(), any(), any()))
                .thenReturn(new CanonicalCommitResult.Reused(reused));

        var reusedResult = new CommitResultStage(repository, recorder).execute(context);

        assertEquals(StageSignal.SUCCESS, reusedResult.signal());
        assertEquals(reusedResultBody, context.validatedResult());
        assertTrue(recorder.getEvents().stream()
                .anyMatch(event -> event.type() == TraceEventType.CANONICAL_REUSE));
    }

    @Test
    void uncertainReadsBackTheSameIdentityAndReturnsTheDurableArtifact() {
        var repository = mock(AnalysisResultRepository.class);
        var recorder = new InMemoryTraceRecorder();
        var context = context();
        var durable = artifact(21L, FixtureFactory.analysisResult());
        when(repository.commitCanonical(any(), any(), any(), any(), any()))
                .thenReturn(new CanonicalCommitResult.Uncertain("COMMIT_TIMEOUT"));
        when(repository.readBack(context.analysisIdentity()))
                .thenReturn(new DbReadResult<>(DbReadOutcome.FOUND, durable, null));

        var result = new CommitResultStage(repository, recorder).execute(context);

        assertEquals(StageSignal.SUCCESS, result.signal());
        assertEquals(durable.result(), context.validatedResult());
        verify(repository, times(1)).readBack(context.analysisIdentity());
        assertTrue(recorder.getEvents().stream().anyMatch(event ->
                event.type() == TraceEventType.READBACK_REQUIRED
                        && context.analysisIdentity().equals(event.attributes().get("identity"))));
    }

    @Test
    void readBackNotFoundAndReadFailedFailedPreserveDifferentFailureStates() {
        var notFoundRepository = mock(AnalysisResultRepository.class);
        var notFoundContext = context();
        when(notFoundRepository.commitCanonical(any(), any(), any(), any(), any()))
                .thenReturn(new CanonicalCommitResult.Uncertain("COMMIT_TIMEOUT"));
        when(notFoundRepository.readBack(notFoundContext.analysisIdentity()))
                .thenReturn(new DbReadResult<>(DbReadOutcome.NOT_FOUND, null, null));

        var notFound = new CommitResultStage(notFoundRepository, new InMemoryTraceRecorder())
                .execute(notFoundContext);

        var failedRepository = mock(AnalysisResultRepository.class);
        var failedContext = context();
        when(failedRepository.commitCanonical(any(), any(), any(), any(), any()))
                .thenReturn(new CanonicalCommitResult.Uncertain("COMMIT_TIMEOUT"));
        when(failedRepository.readBack(failedContext.analysisIdentity()))
                .thenReturn(new DbReadResult<>(DbReadOutcome.READ_FAILED, null, "DB_UNAVAILABLE"));

        var readFailed = new CommitResultStage(failedRepository, new InMemoryTraceRecorder())
                .execute(failedContext);

        assertEquals(StageSignal.FAILED, notFound.signal());
        assertEquals(StageSignal.FAILED, readFailed.signal());
        assertEquals(ErrorCategory.PERSISTENCE, notFound.error().category());
        assertEquals(ErrorCategory.PERSISTENCE, readFailed.error().category());
        assertNotEquals(notFound.error().code(), readFailed.error().code());
    }

    @Test
    void traceFailureDuringReadBackCannotChangeTheBusinessOutcome() {
        var repository = mock(AnalysisResultRepository.class);
        var context = context();
        var durable = artifact(31L, FixtureFactory.analysisResult());
        when(repository.commitCanonical(any(), any(), any(), any(), any()))
                .thenReturn(new CanonicalCommitResult.Uncertain("COMMIT_TIMEOUT"));
        when(repository.readBack(context.analysisIdentity()))
                .thenReturn(new DbReadResult<>(DbReadOutcome.FOUND, durable, null));
        TraceRecorder failingRecorder = new TraceRecorder() {
            @Override
            public void stageStarted(TraceContext trace, StageType stage, int attempt) {
            }

            @Override
            public void stageFinished(
                    TraceContext trace,
                    StageType stage,
                    int attempt,
                    StageExecutionStatus status,
                    long durationMillis,
                    String errorCode) {
            }

            @Override
            public void event(TraceContext trace, TraceEventType type, Map<String, Object> attributes) {
                throw new RuntimeException("trace unavailable");
            }
        };

        var result = new CommitResultStage(repository, failingRecorder).execute(context);

        assertEquals(StageSignal.SUCCESS, result.signal());
        assertEquals(durable.result(), context.validatedResult());
    }

    private AnalysisContext context() {
        var profile = FixtureFactory.candidateProfile(1L, "user-1");
        var job = new JobContext(
                "platform",
                "job-1",
                "title",
                "city",
                3,
                "duration",
                "salary",
                "description",
                FixtureFactory.jobFingerprint()
        );
        var context = new AnalysisContext(
                "trace-1",
                "task-1",
                job,
                profile,
                profile.identity(),
                FixtureFactory.analysisIdentity(),
                ExecutionDeadline.fromNow(Duration.ofSeconds(30), TimeSource.SYSTEM),
                FixtureFactory.analysisDependencySnapshot()
        );
        context.setValidatedResult(FixtureFactory.analysisResult());
        return context;
    }

    private AnalysisArtifact artifact(long artifactId, com.aijob.analyzer.analysis.domain.JobAnalysisResult result) {
        return new AnalysisArtifact(
                artifactId,
                FixtureFactory.analysisIdentity(),
                FixtureFactory.analysisDependencySnapshot(),
                result,
                Instant.parse("2026-01-01T00:00:00Z")
        );
    }
}
