package com.aijob.analyzer.profile;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.llm.LlmExecutionContext;
import com.aijob.analyzer.llm.LlmSemanticSnapshot;
import com.aijob.analyzer.llm.ResumeParserService;
import com.aijob.analyzer.llm.recovery.LlmRecoveryPolicy;
import com.aijob.analyzer.llm.recovery.LlmServiceRole;
import com.aijob.analyzer.llm.recovery.RecoveryAction;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.profile.domain.ProfileArtifactRef;
import com.aijob.analyzer.profile.domain.RawResume;
import com.aijob.analyzer.profile.port.CandidateProfileRepository;
import com.aijob.analyzer.retrieval.ProfileProjectionService;
import com.aijob.analyzer.retrieval.domain.ProjectionBuildResult;
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
public class ProfileBuildService {

    private final CandidateProfileRepository repository;
    private final ResumeParserService parserService;
    private final ProfileProjectionService projectionService;
    private final RetryExecutor retryExecutor;
    private final LlmRecoveryPolicy recoveryPolicy;

    public ProfileBuildService(
            CandidateProfileRepository repository,
            ResumeParserService parserService,
            ProfileProjectionService projectionService,
            RetryExecutor retryExecutor,
            LlmRecoveryPolicy recoveryPolicy) {
        this.repository = repository;
        this.parserService = parserService;
        this.projectionService = projectionService;
        this.retryExecutor = retryExecutor;
        this.recoveryPolicy = recoveryPolicy;
    }

    public ProfileArtifactIdentity buildAndPublish(String userId, String sourceId, String sourceTitle, String rawResumeText, LlmSemanticSnapshot snapshot, TraceContext trace, TraceRecorder recorder) {
        String resumeFingerprint = "RES-" + Integer.toHexString(rawResumeText.hashCode());
        String parserFingerprint = "PARSER-V1";
        ProfileArtifactRef artifactRef = repository.createBuilding(userId, resumeFingerprint, parserFingerprint);
        ProfileArtifactIdentity identity = artifactRef.identity();

        try {
            RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(100), 2.0, Duration.ofSeconds(1), 0.1);
            CandidateProfile profile = retryExecutor.execute(
                "ResumeParsing",
                policy,
                null,
                trace,
                recorder,
                attempt -> {
                    try {
                        LlmExecutionContext context = new LlmExecutionContext(snapshot, attempt, 0);
                        RawResume rawResume = new RawResume(userId, sourceId, sourceTitle, rawResumeText, resumeFingerprint);
                        return parserService.parse(rawResume, identity, context, trace, recorder);
                    } catch (Exception e) {
                        RecoveryAction action = recoveryPolicy.decide(LlmServiceRole.RESUME_PARSER, e, attempt, 0);
                        throw new OperationFailureException(new WorkflowError(
                            "PARSE_FAILED",
                            ErrorCategory.PARSE,
                            action == RecoveryAction.RETRY,
                            e.getMessage()
                        ));
                    }
                }
            );

            repository.saveValidatedProfile(profile);

            ProjectionBuildResult projectionResult = projectionService.buildProjections(profile);
            if (projectionResult != ProjectionBuildResult.SUCCESS) {
                repository.markFailed(identity, "Projection build failed");
                return identity;
            }

            repository.markReadyAndPublishCurrent(identity);
            return identity;

        } catch (Exception e) {
            repository.markFailed(identity, e.getMessage());
            return identity;
        }
    }
}
