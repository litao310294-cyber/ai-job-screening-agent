package com.aijob.analyzer.workflow;

import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.analysis.domain.JobDecision;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.profile.domain.ContextOnlyFact;
import com.aijob.analyzer.profile.domain.ProfileFact;
import com.aijob.analyzer.profile.domain.ProfileStatus;
import com.aijob.analyzer.profile.domain.SourceReference;
import com.aijob.analyzer.profile.domain.StructuredFact;
import com.aijob.analyzer.support.FixtureFactory;
import com.aijob.analyzer.support.time.ExecutionDeadline;
import com.aijob.analyzer.support.time.TimeSource;
import com.aijob.analyzer.workflow.domain.WorkflowStatus;
import com.aijob.analyzer.workflow.domain.StageExecutionStatus;
import com.aijob.analyzer.workflow.domain.StageType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisContextTest {

    @Test
    void negativeBusinessDecisionDoesNotForceWorkflowFailure() {
        var profile = FixtureFactory.candidateProfile(1L, "user-1");
        var jobCtx = new JobContext("platform", "job-1", "title", "city", 3, "3 months", "salary", "desc", FixtureFactory.jobFingerprint());

        var ctx = context(jobCtx, profile);

        ctx.setJobDecision(JobDecision.NOT_RECOMMEND);
        ctx.setWorkflowStatus(WorkflowStatus.SUCCESS);

        assertEquals(WorkflowStatus.SUCCESS, ctx.workflowStatus());
        assertEquals(JobDecision.NOT_RECOMMEND, ctx.jobDecision());
    }

    @Test
    void immutableRequestFieldsCannotBeChanged() {
        var profile = FixtureFactory.candidateProfile(1L, "user-1");
        var jobCtx = new JobContext("platform", "job-1", "title", "city", 3, "3 months", "salary", "desc", FixtureFactory.jobFingerprint());
        var deadline = ExecutionDeadline.fromNow(Duration.ofSeconds(60), TimeSource.SYSTEM);
        var snapshot = FixtureFactory.analysisDependencySnapshot();

        var ctx = new AnalysisContext(
                "trace-1",
                "task-1",
                jobCtx,
                profile,
                profile.identity(),
                FixtureFactory.analysisIdentity(),
                deadline,
                snapshot
        );

        assertEquals("trace-1", ctx.traceId());
        assertEquals("task-1", ctx.taskId());
        assertEquals(jobCtx, ctx.jobContext());
        assertEquals(profile.structuredFacts(), ctx.structuredFacts());
        assertEquals(profile.retrievalFacts(), ctx.retrievalFacts());
        assertEquals(profile.identity(), ctx.profileIdentity());
        assertEquals(deadline, ctx.deadline());
        assertEquals(snapshot, ctx.dependencySnapshot());
    }

    @Test
    void exposesTypedIdentityDeadlineAndRoleScopedFactViews() throws Exception {
        var source = new SourceReference("resume-1", "resume", "skills", "Java and family context");
        var structured = new StructuredFact("city", "Shanghai", source);
        var retrieval = new ProfileFact("F0001", 1L, "skill", "Java", source);
        var contextOnly = new ContextOnlyFact("family", "private", source);
        var profile = new CandidateProfile(
                1L,
                FixtureFactory.profileIdentity("user-1", 1),
                "resume-fp",
                "parser-fp",
                ProfileStatus.READY,
                new ArrayList<>(List.of(structured)),
                new ArrayList<>(List.of(retrieval)),
                new ArrayList<>(List.of(contextOnly))
        );
        var context = new AnalysisContext(
                "trace-1",
                "task-1",
                new JobContext("platform", "job-1", "title", "city", 3, "3 months", "salary", "desc", FixtureFactory.jobFingerprint()),
                profile,
                profile.identity(),
                FixtureFactory.analysisIdentity(),
                ExecutionDeadline.fromNow(Duration.ofSeconds(60), TimeSource.SYSTEM),
                FixtureFactory.analysisDependencySnapshot()
        );

        assertEquals(ExecutionDeadline.class, AnalysisContext.class.getMethod("deadline").getReturnType());
        assertEquals(FixtureFactory.analysisIdentity(),
                AnalysisContext.class.getMethod("analysisIdentity").invoke(context));
        assertEquals(List.of(structured),
                AnalysisContext.class.getMethod("structuredFacts").invoke(context));
        assertEquals(List.of(retrieval),
                AnalysisContext.class.getMethod("retrievalFacts").invoke(context));
        assertFalse(Arrays.stream(AnalysisContext.class.getMethods())
                .anyMatch(method -> method.getName().equals("candidateProfile")));
    }

    @Test
    void ruleStageCannotPassRetrievalOrContextOnlyFactsToRuleService() {
        var source = new SourceReference("resume-1", "resume", "skills", "Java");
        var profile = new CandidateProfile(
                1L,
                FixtureFactory.profileIdentity("user-1", 1),
                "resume-fp",
                "parser-fp",
                ProfileStatus.READY,
                List.of(new StructuredFact("city", "Shanghai", source)),
                List.of(new ProfileFact("F0001", 1L, "skill", "Java", source)),
                List.of(new ContextOnlyFact("family", "private", source))
        );
        var context = new AnalysisContext(
                "trace-1",
                "task-1",
                new JobContext("platform", "job-1", "title", "city", 3, "3 months", "salary", "desc", FixtureFactory.jobFingerprint()),
                profile,
                profile.identity(),
                FixtureFactory.analysisIdentity(),
                ExecutionDeadline.fromNow(Duration.ofSeconds(60), TimeSource.SYSTEM),
                FixtureFactory.analysisDependencySnapshot()
        );
        var capturedProfiles = new ArrayList<CandidateProfile>();
        var stage = new com.aijob.analyzer.workflow.stage.RuleCheckStage((job, candidate) -> {
            capturedProfiles.add(candidate);
            return new com.aijob.analyzer.workflow.domain.RuleResult(
                    com.aijob.analyzer.workflow.domain.RuleSignal.CONTINUE,
                    List.of()
            );
        });

        stage.execute(context);

        assertEquals(1, capturedProfiles.getFirst().structuredFacts().size());
        assertTrue(capturedProfiles.getFirst().retrievalFacts().isEmpty());
        assertTrue(capturedProfiles.getFirst().contextOnly().isEmpty());
    }

    @Test
    void everyStageStartsWithAnExplicitNotStartedStatus() throws Exception {
        var profile = FixtureFactory.candidateProfile(1L, "user-1");
        var job = new JobContext("platform", "job-1", "title", "city", 3, "3 months", "salary", "desc", FixtureFactory.jobFingerprint());
        var context = context(job, profile);
        var statusMethod = AnalysisContext.class.getMethod("stageStatus", StageType.class);

        for (StageType stage : StageType.values()) {
            assertEquals(StageExecutionStatus.NOT_STARTED, statusMethod.invoke(context, stage));
        }
    }

    private AnalysisContext context(JobContext jobContext, CandidateProfile profile) {
        return new AnalysisContext(
                "trace-1",
                "task-1",
                jobContext,
                profile,
                profile.identity(),
                FixtureFactory.analysisIdentity(),
                ExecutionDeadline.fromNow(Duration.ofSeconds(60), TimeSource.SYSTEM),
                FixtureFactory.analysisDependencySnapshot()
        );
    }
}
