package com.aijob.analyzer.workflow;

import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.support.FixtureFactory;
import com.aijob.analyzer.support.InMemoryTraceRecorder;
import com.aijob.analyzer.support.time.ExecutionDeadline;
import com.aijob.analyzer.support.time.TimeSource;
import com.aijob.analyzer.trace.TraceEventType;
import com.aijob.analyzer.workflow.domain.ErrorCategory;
import com.aijob.analyzer.workflow.domain.StageSignal;
import com.aijob.analyzer.workflow.domain.WorkflowError;
import com.aijob.analyzer.workflow.port.ResultValidator;
import com.aijob.analyzer.workflow.port.SafeResultRepair;
import com.aijob.analyzer.workflow.stage.ResultValidateStage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultValidateStageTest {

    @Test
    void safeRepairIsTracedAndTheRepairedResultIsFullyRevalidated() {
        var recorder = new InMemoryTraceRecorder();
        var validationCalls = new AtomicInteger();
        var repairCalls = new AtomicInteger();
        JobAnalysisResult original = FixtureFactory.analysisResult();
        JobAnalysisResult repaired = new com.aijob.analyzer.analysis.domain.JobAnalysisResult(
                original.decision(),
                "repaired",
                original.requirementAssessments(),
                original.risks(),
                original.strengths(),
                original.recommendation(),
                original.summary()
        );
        var repairableError = new WorkflowError(
                "SAFE_REPAIR_AVAILABLE",
                ErrorCategory.PARSE,
                false,
                "safe local repair available"
        );
        ResultValidator validator = (candidate, ignored) -> {
            int call = validationCalls.incrementAndGet();
            if (call == 1) {
                assertSame(original, candidate);
                return Optional.of(repairableError);
            }
            assertSame(repaired, candidate);
            return Optional.empty();
        };
        SafeResultRepair repair = (candidate, error, ignored) -> {
            repairCalls.incrementAndGet();
            assertSame(original, candidate);
            assertSame(repairableError, error);
            return Optional.of(repaired);
        };
        var context = context(original);

        var result = new ResultValidateStage(recorder, validator, repair).execute(context);

        assertEquals(StageSignal.SUCCESS, result.signal());
        assertEquals(2, validationCalls.get());
        assertEquals(1, repairCalls.get());
        assertSame(repaired, context.validatedResult());
        assertTrue(recorder.getEvents().stream()
                .anyMatch(event -> event.type() == TraceEventType.REPAIR_APPLIED));
    }

    @Test
    void failedRevalidationRemainsAValidationFailure() {
        var error = new WorkflowError("INVALID_RESULT", ErrorCategory.SCHEMA, false, "invalid");
        var calls = new AtomicInteger();
        ResultValidator validator = (candidate, ignored) -> {
            calls.incrementAndGet();
            return Optional.of(error);
        };
        SafeResultRepair repair = (candidate, ignoredError, ignored) -> Optional.of(candidate);
        var context = context(FixtureFactory.analysisResult());

        var result = new ResultValidateStage(new InMemoryTraceRecorder(), validator, repair)
                .execute(context);

        assertEquals(StageSignal.FAILED, result.signal());
        assertSame(error, result.error());
        assertEquals(2, calls.get());
    }

    private AnalysisContext context(JobAnalysisResult rawResult) {
        var profile = FixtureFactory.candidateProfile(1L, "user-1");
        var context = new AnalysisContext(
                "trace-1",
                "task-1",
                new JobContext("platform", "job-1", "title", "city", 3, "3 months", "salary", "desc", FixtureFactory.jobFingerprint()),
                profile,
                profile.identity(),
                FixtureFactory.analysisIdentity(),
                ExecutionDeadline.fromNow(Duration.ofSeconds(30), TimeSource.SYSTEM),
                FixtureFactory.analysisDependencySnapshot()
        );
        context.setRawResult(rawResult);
        return context;
    }
}
