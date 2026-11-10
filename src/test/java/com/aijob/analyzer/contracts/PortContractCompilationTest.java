package com.aijob.analyzer.contracts;

import com.aijob.analyzer.analysis.CanonicalCommitResult;
import com.aijob.analyzer.analysis.domain.AnalysisArtifact;
import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.analysis.identity.AnalysisDependencySnapshot;
import com.aijob.analyzer.analysis.identity.AnalysisDependencyFingerprint;
import com.aijob.analyzer.analysis.identity.RetrievalSemanticFingerprint;
import com.aijob.analyzer.analysis.identity.LlmSemanticFingerprint;
import com.aijob.analyzer.analysis.identity.AnalysisIdentity;
import com.aijob.analyzer.analysis.identity.JobFingerprint;
import com.aijob.analyzer.analysis.port.AnalysisResultRepository;
import com.aijob.analyzer.cache.model.CacheAccessStatus;
import com.aijob.analyzer.cache.model.CacheLookupOutcome;
import com.aijob.analyzer.cache.model.CacheReadResult;
import com.aijob.analyzer.cache.port.ArtifactCache;
import com.aijob.analyzer.llm.port.ChatModelClient;
import com.aijob.analyzer.llm.port.ChatModelRequest;
import com.aijob.analyzer.llm.port.ChatModelResponse;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.persistence.model.DbReadResult;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.profile.port.CandidateProfileRepository;
import com.aijob.analyzer.requirement.domain.RequirementSet;
import com.aijob.analyzer.requirement.port.RequirementSetRepository;
import com.aijob.analyzer.retrieval.port.EmbeddingClient;
import com.aijob.analyzer.retrieval.port.EmbeddingResponse;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceRecorder;
import com.aijob.analyzer.workflow.port.RuleCheckService;
import com.aijob.analyzer.analysis.domain.RequirementAssessment;
import com.aijob.analyzer.analysis.domain.JobDecision;
import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.requirement.domain.ValidatedRequirementPayload;
import com.aijob.analyzer.analysis.identity.RequirementIdentity;
import com.aijob.analyzer.analysis.identity.ParserDependencyFingerprint;
import com.aijob.analyzer.analysis.domain.JobContext;
import com.aijob.analyzer.workflow.domain.RuleResult;
import com.aijob.analyzer.workflow.domain.RuleSignal;
import com.aijob.analyzer.llm.LlmSemanticSnapshot;
import com.aijob.analyzer.retrieval.RetrievalSemanticSnapshot;
import com.aijob.analyzer.workflow.domain.StageType;
import com.aijob.analyzer.workflow.domain.StageExecutionStatus;
import com.aijob.analyzer.trace.TraceEventType;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class PortContractCompilationTest {

    @Test
    void chatModelClientContract() {
        // ChatModelResponse frozen fields: rawContent, providerRequestId, modelId, providerExposedModelVersion, inputTokens, outputTokens, finishReason
        ChatModelClient client = (req) -> new ChatModelResponse("res", "rid", "mid", "v1", 10, 20, "stop");

        // ChatModelRequest frozen fields: semanticSnapshot, systemPrompt, userPrompt
        LlmSemanticSnapshot snapshot = new LlmSemanticSnapshot("p1", "m1", "s1", null);
        ChatModelRequest request = new ChatModelRequest(snapshot, "sys", "user");

        // Verify request accessors
        assertEquals(snapshot, request.semanticSnapshot());
        assertEquals("sys", request.systemPrompt());
        assertEquals("user", request.userPrompt());

        ChatModelResponse response = client.generate(request);
        assertEquals("res", response.rawContent());
        assertEquals("rid", response.providerRequestId());
        assertEquals("mid", response.modelId());
        assertEquals("v1", response.providerExposedModelVersion());
        assertEquals(10, response.inputTokens());
        assertEquals(20, response.outputTokens());
        assertEquals("stop", response.finishReason());
    }

    @Test
    void embeddingClientContract() {
        // EmbeddingResponse frozen fields: vector, modelId, providerExposedModelVersion, inputTokens
        EmbeddingClient client = (mid, text) -> new EmbeddingResponse(new float[]{0.1f}, mid, "v1", 5);
        EmbeddingResponse response = client.embed("mid", "text");
        assertEquals(0.1f, response.vector()[0]);
        assertEquals("mid", response.modelId());
        assertEquals("v1", response.providerExposedModelVersion());
        assertEquals(5, response.inputTokens());
    }

    @Test
    void repositoryContracts() throws Exception {
        // CandidateProfileRepository reflection check
        Class<CandidateProfileRepository> profileRepo = CandidateProfileRepository.class;
        assertMethod(profileRepo, "createBuilding", com.aijob.analyzer.profile.domain.ProfileArtifactRef.class,
                "com.aijob.analyzer.profile.domain.ProfileArtifactRef",
                String.class, String.class, String.class);
        assertMethod(profileRepo, "saveValidatedProfile", void.class,
                "void",
                CandidateProfile.class);
        assertMethod(profileRepo, "find", DbReadResult.class,
                "com.aijob.analyzer.persistence.model.DbReadResult<com.aijob.analyzer.profile.domain.CandidateProfile>",
                ProfileArtifactIdentity.class);
        assertMethod(profileRepo, "findCurrentReady", Optional.class,
                "java.util.Optional<com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity>",
                String.class);
        assertMethod(profileRepo, "markReadyAndPublishCurrent", com.aijob.analyzer.profile.domain.ProfilePublishOutcome.class,
                "com.aijob.analyzer.profile.domain.ProfilePublishOutcome",
                ProfileArtifactIdentity.class);
        assertMethod(profileRepo, "markFailed", void.class,
                "void",
                ProfileArtifactIdentity.class, String.class);

        // RequirementSetRepository reflection check
        Class<RequirementSetRepository> reqRepo = RequirementSetRepository.class;
        assertMethod(reqRepo, "find", DbReadResult.class,
                "com.aijob.analyzer.persistence.model.DbReadResult<com.aijob.analyzer.requirement.domain.RequirementSet>",
                RequirementIdentity.class);
        assertMethod(reqRepo, "insertCanonical", RequirementSet.class,
                "com.aijob.analyzer.requirement.domain.RequirementSet",
                ValidatedRequirementPayload.class);

        // AnalysisResultRepository reflection check
        Class<AnalysisResultRepository> analysisRepo = AnalysisResultRepository.class;
        assertMethod(analysisRepo, "findCanonical", DbReadResult.class,
                "com.aijob.analyzer.persistence.model.DbReadResult<com.aijob.analyzer.analysis.domain.AnalysisArtifact>",
                AnalysisIdentity.class);
        assertMethod(analysisRepo, "commitCanonical", CanonicalCommitResult.class,
                "com.aijob.analyzer.analysis.CanonicalCommitResult",
                AnalysisIdentity.class, JobAnalysisResult.class, AnalysisDependencySnapshot.class, TraceContext.class, TraceRecorder.class);
        assertMethod(analysisRepo, "readBack", DbReadResult.class,
                "com.aijob.analyzer.persistence.model.DbReadResult<com.aijob.analyzer.analysis.domain.AnalysisArtifact>",
                AnalysisIdentity.class);
    }

    private void assertMethod(Class<?> clazz, String methodName, Class<?> returnType, String genericReturnType, Class<?>... parameterTypes) throws Exception {
        Method method = clazz.getMethod(methodName, parameterTypes);
        assertEquals(returnType, method.getReturnType(), "Method " + methodName + " has wrong raw return type");
        assertEquals(genericReturnType, method.getGenericReturnType().getTypeName(), "Method " + methodName + " has wrong generic return type");
    }

    @Test
    void cacheReadResultInvariants() {
        // SUCCESS + HIT + value
        var hit = new CacheReadResult<>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.HIT, "val");
        assertEquals(CacheLookupOutcome.HIT, hit.lookupOutcome());
        assertEquals("val", hit.value());

        // SUCCESS + MISS + no value
        var miss = new CacheReadResult<String>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.MISS, null);
        assertNull(miss.value());

        // SUCCESS + INVALID + no value
        var invalid = new CacheReadResult<String>(CacheAccessStatus.SUCCESS, CacheLookupOutcome.INVALID, null);
        assertNull(invalid.value());

        // TIMEOUT + null lookupOutcome
        var timeout = new CacheReadResult<String>(CacheAccessStatus.TIMEOUT, null, null);
        assertNull(timeout.lookupOutcome());

        // UNAVAILABLE + null lookupOutcome
        var unavailable = new CacheReadResult<String>(CacheAccessStatus.UNAVAILABLE, null, null);
        assertNull(unavailable.lookupOutcome());

        // Illegal combinations
        assertThrows(IllegalArgumentException.class, () -> new CacheReadResult<>(CacheAccessStatus.SUCCESS, null, "val"), "SUCCESS must have lookupOutcome");
        assertThrows(IllegalArgumentException.class, () -> new CacheReadResult<>(CacheAccessStatus.TIMEOUT, CacheLookupOutcome.HIT, "val"));
        assertThrows(IllegalArgumentException.class, () -> new CacheReadResult<>(CacheAccessStatus.UNAVAILABLE, CacheLookupOutcome.MISS, null));
    }

    @Test
    void dbReadResultInvariants() {
        // FOUND -> value present, errorCode absent
        var found = new DbReadResult<>(DbReadOutcome.FOUND, "val", null);
        assertEquals("val", found.value());
        assertNull(found.errorCode());

        // NOT_FOUND -> value absent, errorCode absent
        var notFound = new DbReadResult<String>(DbReadOutcome.NOT_FOUND, null, null);
        assertNull(notFound.value());
        assertNull(notFound.errorCode());

        // READ_FAILED -> value absent, errorCode present
        var failed = new DbReadResult<String>(DbReadOutcome.READ_FAILED, null, "ERR001");
        assertNull(failed.value());
        assertEquals("ERR001", failed.errorCode());

        // Illegal combinations
        assertThrows(IllegalArgumentException.class, () -> new DbReadResult<>(DbReadOutcome.FOUND, null, null), "FOUND must have value");
        assertThrows(IllegalArgumentException.class, () -> new DbReadResult<>(DbReadOutcome.FOUND, "val", "ERR"), "FOUND cannot have errorCode");
        assertThrows(IllegalArgumentException.class, () -> new DbReadResult<>(DbReadOutcome.NOT_FOUND, "val", null), "NOT_FOUND cannot have value");
        assertThrows(IllegalArgumentException.class, () -> new DbReadResult<>(DbReadOutcome.NOT_FOUND, null, "ERR"), "NOT_FOUND cannot have errorCode");
        assertThrows(IllegalArgumentException.class, () -> new DbReadResult<>(DbReadOutcome.READ_FAILED, "val", "ERR"), "READ_FAILED cannot have value");
        assertThrows(IllegalArgumentException.class, () -> new DbReadResult<>(DbReadOutcome.READ_FAILED, null, null), "READ_FAILED must have errorCode");
        assertThrows(IllegalArgumentException.class, () -> new DbReadResult<>(DbReadOutcome.READ_FAILED, null, "  "), "READ_FAILED errorCode cannot be blank");
    }

    @Test
    void canonicalCommitResultVariants() {
        JobFingerprint jf = new JobFingerprint("sha256");
        AnalysisDependencyFingerprint adf = new AnalysisDependencyFingerprint("sha256");
        AnalysisIdentity identity = new AnalysisIdentity(jf, adf);
        AnalysisDependencySnapshot snapshot = new AnalysisDependencySnapshot(
                jf, 1L, null, 1L, null, "v1", null, null, null, null
        );
        JobAnalysisResult result = new JobAnalysisResult(
                JobDecision.RECOMMEND_APPLY,
                "assessment",
                List.of(),
                List.of(),
                List.of(),
                "rec",
                "sum"
        );
        AnalysisArtifact artifact = new AnalysisArtifact(1, identity, snapshot, result, Instant.now());

        CanonicalCommitResult inserted = new CanonicalCommitResult.Inserted(artifact);
        CanonicalCommitResult reused = new CanonicalCommitResult.Reused(artifact);
        CanonicalCommitResult uncertain = new CanonicalCommitResult.Uncertain("ERR");

        assertEquals(artifact, ((CanonicalCommitResult.Inserted) inserted).artifact());
        assertEquals(artifact, ((CanonicalCommitResult.Reused) reused).artifact());
        assertEquals("ERR", ((CanonicalCommitResult.Uncertain) uncertain).errorCode());
    }

    @Test
    void traceEventTypeEnum() {
        Set<String> expected = Set.of(
            "TRANSITION",
            "RETRY_SCHEDULED",
            "REPAIR_APPLIED",
            "FALLBACK_SELECTED",
            "READBACK_REQUIRED",
            "CANONICAL_REUSE",
            "SINGLEFLIGHT_LEADER",
            "SINGLEFLIGHT_FOLLOWER"
        );
        Set<String> actual = Arrays.stream(TraceEventType.values())
                                    .map(Enum::name)
                                    .collect(Collectors.toSet());
        assertEquals(expected, actual, "TraceEventType enum values drift detected");
    }

    @Test
    void traceRecorderContract() {
        TraceContext ctx = new TraceContext("tid", "taskid");
        TraceRecorder recorder = new TraceRecorder() {
            @Override public void stageStarted(TraceContext trace, StageType stage, int attempt) {}
            @Override public void stageFinished(TraceContext trace, StageType stage, int attempt, StageExecutionStatus status, long durationMillis, String errorCode) {}
            @Override public void event(TraceContext trace, TraceEventType type, java.util.Map<String, Object> attributes) {}
        };
        assertNotNull(recorder);
        assertEquals("tid", ctx.traceId());
    }

    @Test
    void ruleCheckContract() {
        RuleCheckService service = (job, profile) -> new RuleResult(RuleSignal.CONTINUE, List.of());
        assertNotNull(service);
    }
}
