package com.aijob.analyzer.analysis;

import com.aijob.analyzer.analysis.concurrency.SingleFlightCoordinator;
import com.aijob.analyzer.analysis.domain.AnalysisArtifact;
import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.analysis.identity.AnalysisDependencySnapshot;
import com.aijob.analyzer.analysis.identity.AnalysisIdentity;
import com.aijob.analyzer.analysis.identity.AnalysisIdentityFactory;
import com.aijob.analyzer.analysis.identity.RequirementIdentity;
import com.aijob.analyzer.analysis.identity.RequirementIdentityFactory;
import com.aijob.analyzer.analysis.port.AnalysisResultRepository;
import com.aijob.analyzer.cache.CacheAsideResolver;
import com.aijob.analyzer.cache.port.ArtifactCache;
import com.aijob.analyzer.llm.LlmSemanticVersions;
import com.aijob.analyzer.profile.CurrentProfileResolver;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.support.time.ExecutionDeadline;
import com.aijob.analyzer.trace.SafeTraceRecorder;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceRecorder;
import com.aijob.analyzer.workflow.AnalysisContext;
import com.aijob.analyzer.workflow.domain.AnalysisExecution;
import com.aijob.analyzer.workflow.domain.WorkflowStatus;
import com.aijob.analyzer.workflow.AnalysisOrchestrator;
import com.aijob.analyzer.support.time.TimeSource;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.Map;

@Service
@Profile("!test")
public class AnalysisApplicationService {

    private final AnalysisIdentityFactory identityFactory;
    private final RequirementIdentityFactory requirementIdentityFactory;
    private final AnalysisResultRepository repository;
    private final ArtifactCache<AnalysisArtifact> cache;
    private final CacheAsideResolver<AnalysisArtifact> cacheResolver;
    private final SingleFlightCoordinator<AnalysisIdentity> singleFlight;
    private final AnalysisOrchestrator orchestrator;
    private final CurrentProfileResolver profileResolver;
    private final LlmSemanticVersions versions;
    private final com.aijob.analyzer.retrieval.RetrievalSemanticSnapshot retrievalSnapshot;
    private final TraceRecorder baseRecorder;
    private com.aijob.analyzer.rules.RulePolicy rulePolicy;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRulePolicy(com.aijob.analyzer.rules.RulePolicy rulePolicy) { this.rulePolicy=rulePolicy; }

    public AnalysisApplicationService(
            AnalysisIdentityFactory identityFactory,
            RequirementIdentityFactory requirementIdentityFactory,
            AnalysisResultRepository repository,
            ArtifactCache<AnalysisArtifact> cache,
            SingleFlightCoordinator<AnalysisIdentity> singleFlight,
            AnalysisOrchestrator orchestrator,
            CurrentProfileResolver profileResolver,
            LlmSemanticVersions versions,
            com.aijob.analyzer.retrieval.RetrievalSemanticSnapshot retrievalSnapshot,
            TraceRecorder baseRecorder) {
        this.identityFactory = Objects.requireNonNull(identityFactory);
        this.requirementIdentityFactory = Objects.requireNonNull(requirementIdentityFactory);
        this.repository = Objects.requireNonNull(repository);
        this.cache = Objects.requireNonNull(cache);
        this.cacheResolver = new CacheAsideResolver<>();
        this.singleFlight = Objects.requireNonNull(singleFlight);
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.profileResolver = Objects.requireNonNull(profileResolver);
        this.versions = Objects.requireNonNull(versions);
        this.retrievalSnapshot = Objects.requireNonNull(retrievalSnapshot);
        this.baseRecorder = Objects.requireNonNull(baseRecorder);
    }

    public AnalysisExecution analyze(String userId, JobContext job) {
        var run = execute(prepare(userId, job), new TraceContext(UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        if (run.execution().status() == WorkflowStatus.FAILED) {
            throw new RuntimeException("Workflow failed: " + run.execution().degradedReason());
        }
        return run.execution();
    }

    public PreparedAnalysis prepare(String userId, JobContext job) {
        CandidateProfile profile = profileResolver.resolveCurrent(userId)
                .orElseThrow(() -> new ProfileNotReadyException(userId));
        var frozenRules=rulePolicy==null?null:rulePolicy.freeze(job,profile);

        // 2. Freeze identities and dependency snapshot
        RequirementIdentity reqId = requirementIdentityFactory.create(job.jobFingerprint(), versions.getRequirementParserSnapshot());

        com.aijob.analyzer.analysis.identity.RetrievalSemanticFingerprint retFp = identityFactory.retrievalFingerprint(retrievalSnapshot);
        com.aijob.analyzer.analysis.identity.LlmSemanticFingerprint llmFp = identityFactory.finalLlmFingerprint(versions.getFinalAnalysisSnapshot());

        AnalysisDependencySnapshot initialSnapshot = new AnalysisDependencySnapshot(
                job.jobFingerprint(),
                profile.artifactId(),
                profile.identity(),
                0L, // Placeholder
                reqId,
                frozenRules==null?"RULE-V1":frozenRules.snapshot().identity(),
                retFp,
                retrievalSnapshot,
                llmFp,
                versions.getFinalAnalysisSnapshot()
        );

        AnalysisIdentity analysisId = identityFactory.create(initialSnapshot);
        return new PreparedAnalysis(job, profile, initialSnapshot, analysisId,
                frozenRules==null?null:frozenRules.snapshot(),frozenRules==null?null:frozenRules.result());
    }

    public AnalysisRun execute(PreparedAnalysis prepared, TraceContext trace) {
        String traceId = trace.traceId();
        String taskId = trace.taskId();
        JobContext job = prepared.job();
        CandidateProfile profile = prepared.profile();
        AnalysisDependencySnapshot initialSnapshot = prepared.snapshot();
        AnalysisIdentity analysisId = prepared.identity();
        TraceRecorder recorder = new SafeTraceRecorder(baseRecorder);
        var source = new java.util.concurrent.atomic.AtomicReference<>("SHARED_COMPUTE");
        recorder.event(trace, com.aijob.analyzer.trace.TraceEventType.TRANSITION, Map.of(
                "phase", "IDENTITY_FROZEN",
                "profileVersion", profile.identity().profileVersion(),
                "profileArtifactId", profile.artifactId(),
                "analysisDependencyFingerprint", analysisId.analysisDependencyFingerprint().value()
        ));

        // 3. Cache aside resolution
        AnalysisArtifact artifact;
        try {
            artifact = cacheResolver.resolve(
                trace,
                recorder,
                () -> {
                    var read = cache.get(analysisId.analysisDependencyFingerprint().value());
                    if (read.lookupOutcome() == com.aijob.analyzer.cache.model.CacheLookupOutcome.HIT) source.set("REDIS");
                    return read;
                },
                () -> {
                    var read = repository.findCanonical(analysisId);
                    if (read.outcome() == com.aijob.analyzer.persistence.model.DbReadOutcome.FOUND) source.set("DATABASE");
                    return read;
                },
                () -> {
                    // 4. SingleFlight for compute
                    return singleFlight.execute(
                            analysisId,
                            Duration.ofSeconds(30), // Follower wait timeout
                            trace,
                            recorder,
                            () -> {
                                source.set("COMPUTED");
                                // 5. Actual Workflow compute
                                AnalysisContext context = new AnalysisContext(
                                        traceId,
                                        taskId,
                                        job,
                                        profile,
                                        profile.identity(),
                                        analysisId,
                                        ExecutionDeadline.fromNow(Duration.ofMinutes(2), TimeSource.SYSTEM),
                                        initialSnapshot,
                                        recorder
                                );
                                context.setFrozenRuleResult(prepared.ruleResult());
                                AnalysisExecution execution = orchestrator.execute(context);
                                if (execution.resultOrigin() == com.aijob.analyzer.workflow.domain.ResultOrigin.RULE_TERMINAL
                                        || execution.status() != WorkflowStatus.SUCCESS) {
                                    throw new TerminalWorkflowResultException(execution,
                                            context.lastError() == null ? null : context.lastError().code());
                                }

                                // Retrieve the artifact from DB after commit (CommitResultStage handles this)
                                var readBack = repository.readBack(analysisId);
                                if (readBack.outcome() == com.aijob.analyzer.persistence.model.DbReadOutcome.FOUND) {
                                    return readBack.value();
                                }
                                throw new RuntimeException("Artifact not found after successful workflow");
                            }
                    );
                },
                result -> cache.put(analysisId.analysisDependencyFingerprint().value(), result, Duration.ofHours(24))
            );
        } catch (TerminalWorkflowResultException terminal) {
            recorder.event(trace, com.aijob.analyzer.trace.TraceEventType.TRANSITION, Map.of(
                    "phase", "TERMINAL_RESULT",
                    "profileVersion", profile.identity().profileVersion(),
                    "profileArtifactId", profile.artifactId(),
                    "analysisDependencyFingerprint", analysisId.analysisDependencyFingerprint().value()
            ));
            return new AnalysisRun(terminal.execution, source.get(), null, initialSnapshot, terminal.errorCode);
        }

        recorder.event(trace, com.aijob.analyzer.trace.TraceEventType.TRANSITION, Map.of(
                "phase", "EXECUTION_COMPLETED",
                "profileVersion", profile.identity().profileVersion(),
                "profileArtifactId", profile.artifactId(),
                "analysisDependencyFingerprint", analysisId.analysisDependencyFingerprint().value()
        ));

        return new AnalysisRun(new AnalysisExecution(
                com.aijob.analyzer.workflow.domain.WorkflowStatus.SUCCESS,
                com.aijob.analyzer.workflow.domain.ResultOrigin.LLM_ANALYSIS, // May need to be smarter about this
                artifact.result(),
                null
        ), source.get(), artifact.artifactId(), artifact.dependencySnapshot(), null);
    }

    private static final class TerminalWorkflowResultException extends RuntimeException {
        private final AnalysisExecution execution;
        private final String errorCode;

        private TerminalWorkflowResultException(AnalysisExecution execution, String errorCode) {
            super("workflow produced a non-canonical terminal result");
            this.execution = execution;
            this.errorCode = errorCode;
        }
    }

    public static final class ProfileNotReadyException extends RuntimeException {
        public ProfileNotReadyException(String userId) { super("No READY profile found for user: " + userId); }
    }
}
