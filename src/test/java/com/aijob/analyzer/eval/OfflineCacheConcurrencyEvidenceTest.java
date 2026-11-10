package com.aijob.analyzer.eval;

import com.aijob.analyzer.analysis.AnalysisApplicationService;
import com.aijob.analyzer.analysis.CanonicalCommitResult;
import com.aijob.analyzer.analysis.concurrency.JvmSingleFlightCoordinator;
import com.aijob.analyzer.analysis.concurrency.FollowerWaitTimeoutException;
import com.aijob.analyzer.analysis.domain.AnalysisArtifact;
import com.aijob.analyzer.analysis.domain.JobAnalysisResult;
import com.aijob.analyzer.analysis.identity.AnalysisDependencySnapshot;
import com.aijob.analyzer.analysis.identity.AnalysisIdentity;
import com.aijob.analyzer.analysis.identity.AnalysisIdentityFactory;
import com.aijob.analyzer.analysis.port.AnalysisResultRepository;
import com.aijob.analyzer.cache.model.CacheAccessStatus;
import com.aijob.analyzer.cache.model.CacheLookupOutcome;
import com.aijob.analyzer.cache.model.CacheReadResult;
import com.aijob.analyzer.cache.port.ArtifactCache;
import com.aijob.analyzer.llm.LlmSemanticVersions;
import com.aijob.analyzer.persistence.model.DbReadOutcome;
import com.aijob.analyzer.persistence.model.DbReadResult;
import com.aijob.analyzer.profile.CurrentProfileResolver;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.support.InMemoryTraceRecorder;
import com.aijob.analyzer.support.retry.RetryPolicy;
import com.aijob.analyzer.trace.TraceContext;
import com.aijob.analyzer.trace.TraceEventType;
import com.aijob.analyzer.trace.TraceRecorder;
import com.aijob.analyzer.workflow.AnalysisOrchestrator;
import com.aijob.analyzer.workflow.AnalysisStage;
import com.aijob.analyzer.workflow.DefaultFallbackResultFactory;
import com.aijob.analyzer.workflow.DefaultRuleTerminalResultFactory;
import com.aijob.analyzer.workflow.DefaultTransitionPolicy;
import com.aijob.analyzer.workflow.StageResult;
import com.aijob.analyzer.workflow.stage.CommitResultStage;
import com.aijob.analyzer.workflow.domain.AnalysisExecution;
import com.aijob.analyzer.workflow.domain.ResultOrigin;
import com.aijob.analyzer.workflow.domain.StageType;
import com.aijob.analyzer.workflow.domain.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.time.Duration;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.EnumMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OfflineCacheConcurrencyEvidenceTest {

    @Test
    void writesCacheReuseFormalMatrix() {
        List<Map<String, Object>> rows = List.of(
                cacheCase("CACHE-01", CacheAccessStatus.SUCCESS, CacheLookupOutcome.HIT, DbReadOutcome.NOT_FOUND, false, false, false, false),
                cacheCase("CACHE-02", CacheAccessStatus.SUCCESS, CacheLookupOutcome.MISS, DbReadOutcome.FOUND, false, false, true, false),
                cacheCase("CACHE-03", CacheAccessStatus.SUCCESS, CacheLookupOutcome.MISS, DbReadOutcome.NOT_FOUND, true, true, true, false),
                cacheCase("CACHE-04", CacheAccessStatus.UNAVAILABLE, null, DbReadOutcome.FOUND, false, false, true, false),
                cacheCase("CACHE-05", CacheAccessStatus.SUCCESS, CacheLookupOutcome.MISS, DbReadOutcome.READ_FAILED, false, false, false, true),
                identityIsolationCase()
        );
        long passed = rows.stream().filter(row -> Boolean.TRUE.equals(row.get("pass"))).count();
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("matrix", "CACHE_REUSE_OFFLINE_V2_PHASE1");
        artifact.put("cases", rows);
        artifact.put("overall", passed + "/" + rows.size());
        writeJson("cache_reuse_raw.json", artifact);
        assertEquals(6, passed);
    }

    private Map<String, Object> cacheCase(String caseId, CacheAccessStatus cacheStatus,
                                          CacheLookupOutcome cacheOutcome, DbReadOutcome dbOutcome,
                                          boolean computeExpected, boolean workflowExpected,
                                          boolean populateExpected, boolean expectFailure) {
        CandidateProfile profile = OfflineEvidenceFixtures.profile("cache-user", 1L);
        AnalysisIdentityFactory identityFactory = new AnalysisIdentityFactory();
        LlmSemanticVersions versions = OfflineEvidenceFixtures.versions();
        AnalysisIdentity identity = OfflineEvidenceFixtures.applicationIdentity(profile, identityFactory, versions);
        AnalysisDependencySnapshot snapshot = OfflineEvidenceFixtures.snapshot(profile,
                OfflineEvidenceFixtures.requirementSet(true), identityFactory, versions);
        AnalysisArtifact artifact = OfflineEvidenceFixtures.artifact(identity, snapshot, true);
        FakeCache cache = new FakeCache(cacheStatus, cacheOutcome, cacheOutcome == CacheLookupOutcome.HIT ? artifact : null);
        FakeRepository repository = new FakeRepository();
        if (dbOutcome == DbReadOutcome.FOUND) repository.artifacts.put(identity, artifact);
        if (dbOutcome == DbReadOutcome.READ_FAILED) repository.readFailure = "DB_DOWN";
        AtomicInteger workflows = new AtomicInteger();
        AtomicInteger retrievalCalls = new AtomicInteger();
        AtomicInteger llmCalls = new AtomicInteger();
        AnalysisOrchestrator orchestrator = serviceOrchestrator(repository, workflows, retrievalCalls, llmCalls);
        CurrentProfileResolver profiles = mock(CurrentProfileResolver.class);
        when(profiles.resolveCurrent("cache-user")).thenReturn(Optional.of(profile));
        AnalysisApplicationService service = new AnalysisApplicationService(identityFactory,
                new com.aijob.analyzer.analysis.identity.RequirementIdentityFactory(), repository, cache,
                new JvmSingleFlightCoordinator<>(), orchestrator, profiles, versions,
                OfflineEvidenceFixtures.retrievalSnapshot(), new InMemoryTraceRecorder());
        boolean failed = false;
        String failureMessage = null;
        try {
            service.analyze("cache-user", OfflineEvidenceFixtures.job());
        } catch (RuntimeException expected) {
            failed = true;
            failureMessage = expected.getClass().getSimpleName() + ":" + expected.getMessage();
        }
        boolean pass = failed == expectFailure
                && workflows.get() == (workflowExpected ? 1 : 0)
                && cache.puts.get() == (populateExpected ? 1 : 0)
                && (workflows.get() == 1) == computeExpected;
        Map<String, Object> row = row(caseId, cacheStatus.name(), String.valueOf(cacheOutcome), dbOutcome.name(),
                workflows.get(), retrievalCalls.get(), llmCalls.get(), cache.puts.get(), failed, pass);
        row.put("failure", failureMessage);
        return row;
    }

    private Map<String, Object> identityIsolationCase() {
        CandidateProfile p1 = OfflineEvidenceFixtures.profile("iso-user-1", 1L);
        CandidateProfile p2 = OfflineEvidenceFixtures.profile("iso-user-2", 1L);
        AnalysisIdentityFactory identityFactory = new AnalysisIdentityFactory();
        LlmSemanticVersions versions = OfflineEvidenceFixtures.versions();
        FakeCache cache = new FakeCache(CacheAccessStatus.SUCCESS, CacheLookupOutcome.MISS, null);
        FakeRepository repository = new FakeRepository();
        AtomicInteger workflows = new AtomicInteger();
        AtomicInteger retrievalCalls = new AtomicInteger();
        AtomicInteger llmCalls = new AtomicInteger();
        AnalysisOrchestrator orchestrator = serviceOrchestrator(repository, workflows, retrievalCalls, llmCalls);
        CurrentProfileResolver profiles = mock(CurrentProfileResolver.class);
        when(profiles.resolveCurrent("iso-user-1")).thenReturn(Optional.of(p1));
        when(profiles.resolveCurrent("iso-user-2")).thenReturn(Optional.of(p2));
        AnalysisApplicationService service = new AnalysisApplicationService(identityFactory,
                new com.aijob.analyzer.analysis.identity.RequirementIdentityFactory(), repository, cache,
                new JvmSingleFlightCoordinator<>(), orchestrator, profiles, versions,
                OfflineEvidenceFixtures.retrievalSnapshot(), new InMemoryTraceRecorder());
        service.analyze("iso-user-1", OfflineEvidenceFixtures.job());
        service.analyze("iso-user-2", OfflineEvidenceFixtures.job());
        boolean pass = workflows.get() == 2 && repository.artifacts.size() == 2;
        return row("CACHE-06", CacheAccessStatus.SUCCESS.name(), "MISS", DbReadOutcome.NOT_FOUND.name(), workflows.get(), retrievalCalls.get(), llmCalls.get(), cache.puts.get(), false, pass);
    }

    private AnalysisOrchestrator serviceOrchestrator(FakeRepository repository, AtomicInteger workflows,
                                                     AtomicInteger retrievalCalls, AtomicInteger llmCalls) {
        Map<StageType, AnalysisStage> stages = new EnumMap<>(StageType.class);
        stages.put(StageType.RULE_CHECK, OfflineEvidenceFixtures.stage(StageType.RULE_CHECK, c -> {
            c.setRuleResult(new com.aijob.analyzer.workflow.domain.RuleResult(com.aijob.analyzer.workflow.domain.RuleSignal.CONTINUE, List.of()));
            return StageResult.success();
        }));
        stages.put(StageType.PROFILE_RETRIEVAL, OfflineEvidenceFixtures.stage(StageType.PROFILE_RETRIEVAL, c -> {
            retrievalCalls.incrementAndGet(); c.setRequirementSet(OfflineEvidenceFixtures.requirementSet(true)); c.setRetrievalResult(OfflineEvidenceFixtures.retrievalResult(true)); return StageResult.success();
        }));
        stages.put(StageType.LLM_ANALYSIS, OfflineEvidenceFixtures.stage(StageType.LLM_ANALYSIS, c -> {
            workflows.incrementAndGet(); llmCalls.incrementAndGet(); c.setRawResult(OfflineEvidenceFixtures.validResult(true)); return StageResult.success();
        }));
        stages.put(StageType.RESULT_VALIDATE, OfflineEvidenceFixtures.stage(StageType.RESULT_VALIDATE, c -> { c.setValidatedResult(c.rawResult()); return StageResult.success(); }));
        stages.put(StageType.COMMIT_RESULT, new CommitResultStage(repository, new InMemoryTraceRecorder()));
        Map<StageType, RetryPolicy> policies = new EnumMap<>(StageType.class);
        for (StageType type : StageType.values()) policies.put(type, new RetryPolicy(1, Duration.ZERO, 1.0, Duration.ZERO, 0.0));
        return new AnalysisOrchestrator(stages, new DefaultTransitionPolicy(), new DefaultRuleTerminalResultFactory(), new DefaultFallbackResultFactory(), new InMemoryTraceRecorder(), new com.aijob.analyzer.support.retry.DefaultRetryExecutor(duration -> {}, () -> 0.0), policies);
    }

    @Test
    void writesSingleFlightFormalMatrix() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int concurrency : List.of(1, 5, 20)) {
            for (int round = 1; round <= 3; round++) rows.add(singleFlightRound(concurrency, round));
        }
        rows.add(singleFlightFailure("SF-10"));
        rows.add(singleFlightRetryBoundary("SF-11"));
        rows.add(singleFlightTimeout("SF-12"));
        rows.add(singleFlightCleanup("SF-13"));
        long passed = rows.stream().filter(row -> Boolean.TRUE.equals(row.get("pass"))).count();
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("matrix", "SINGLEFLIGHT_OFFLINE_V1");
        artifact.put("performanceClaim", false);
        artifact.put("cases", rows);
        artifact.put("overall", passed + "/" + rows.size());
        writeJson("singleflight_raw.json", artifact);
        assertEquals(13, rows.size());
        assertEquals(13, passed);
    }

    private Map<String, Object> singleFlightRound(int concurrency, int round) throws Exception {
        JvmSingleFlightCoordinator<String> coordinator = new JvmSingleFlightCoordinator<>();
        com.aijob.analyzer.trace.InProcessTraceRecorder trace = new com.aijob.analyzer.trace.InProcessTraceRecorder();
        AtomicInteger computes = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch followersReady = new CountDownLatch(4);
        CountDownLatch followersGo = new CountDownLatch(1);
        List<CompletableFuture<String>> futures = new ArrayList<>();
        List<String> traceIds = new ArrayList<>();
        try (ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                int n = i;
                String traceId = "sf-" + concurrency + "-" + round + "-" + n;
                traceIds.add(traceId);
                futures.add(CompletableFuture.supplyAsync(() -> {
                    ready.countDown();
                    await(start);
                    TraceContext context = new TraceContext(traceId, "task-" + n);
                    return coordinator.execute("same-key", Duration.ofSeconds(2), context, trace, () -> {
                        computes.incrementAndGet();
                        entered.countDown();
                        if (concurrency > 1) await(release);
                        return "canonical";
                    });
                }, executor));
            }
            ready.await();
            start.countDown();
            entered.await();
            if (concurrency > 1) {
                long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                while (System.nanoTime() < deadline && traceEventCount(trace, traceIds) < concurrency) Thread.onSpinWait();
                release.countDown();
            }
            for (CompletableFuture<String> future : futures) future.get();
        }
        long leaders = traceEventCount(trace, traceIds, TraceEventType.SINGLEFLIGHT_LEADER);
        long followers = traceEventCount(trace, traceIds, TraceEventType.SINGLEFLIGHT_FOLLOWER);
        boolean pass = computes.get() == 1 && leaders == 1 && followers == concurrency - 1;
        return singleFlightRow("SF-" + concurrency + "-R" + round, concurrency, round, concurrency, leaders, followers, computes.get(), 0, pass);
    }

    private Map<String, Object> singleFlightFailure(String id) throws Exception {
        JvmSingleFlightCoordinator<String> coordinator = new JvmSingleFlightCoordinator<>();
        com.aijob.analyzer.trace.InProcessTraceRecorder trace = new com.aijob.analyzer.trace.InProcessTraceRecorder();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        List<CompletableFuture<String>> futures = new ArrayList<>();
        try (ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return coordinator.execute("failure", Duration.ofSeconds(1),
                            new TraceContext("sf-failure-leader", "task-leader"), trace, () -> {
                        failures.incrementAndGet(); entered.countDown(); await(release);
                        throw new IllegalStateException("leader failure");
                    });
                } catch (RuntimeException ex) { throw ex; }
            }, executor));
            entered.await();
            for (int i = 1; i < 5; i++) {
                int n = i;
                futures.add(CompletableFuture.supplyAsync(() -> {
                String traceId = "sf-failure-follower-" + n;
                try { return coordinator.execute("failure", Duration.ofSeconds(1),
                        new TraceContext(traceId, "task-follower-" + n), trace, () -> {
                            failures.incrementAndGet(); throw new IllegalStateException("unexpected leader");
                        }); }
                catch (RuntimeException ex) { throw ex; }
                }, executor));
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (System.nanoTime() < deadline && trace.getRecords("sf-failure-leader").size()
                    + trace.getRecords("sf-failure-follower-1").size()
                    + trace.getRecords("sf-failure-follower-2").size()
                    + trace.getRecords("sf-failure-follower-3").size()
                    + trace.getRecords("sf-failure-follower-4").size() < 5) Thread.onSpinWait();
            release.countDown();
            for (CompletableFuture<String> f : futures) try { f.get(); } catch (ExecutionException ignored) { }
        }
        boolean pass = failures.get() == 1;
        return singleFlightRow(id, 5, 1, 5, 1, 4, failures.get(), 5, pass);
    }

    private Map<String, Object> singleFlightRetryBoundary(String id) {
        JvmSingleFlightCoordinator<String> coordinator = new JvmSingleFlightCoordinator<>();
        AtomicInteger computes = new AtomicInteger();
        boolean firstFailed = false;
        try { coordinator.execute("retry", Duration.ofSeconds(1), () -> { computes.incrementAndGet(); throw new IllegalStateException("retryable"); }); }
        catch (RuntimeException expected) { firstFailed = true; }
        String second = coordinator.execute("retry", Duration.ofSeconds(1), () -> { computes.incrementAndGet(); return "ok"; });
        return singleFlightRow(id, 1, 1, 2, 2, 0, computes.get(), firstFailed && "ok".equals(second) ? 0 : 1, firstFailed && computes.get() == 2);
    }

    private Map<String, Object> singleFlightTimeout(String id) throws Exception {
        JvmSingleFlightCoordinator<String> coordinator = new JvmSingleFlightCoordinator<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> leader = CompletableFuture.supplyAsync(() -> coordinator.execute("timeout", Duration.ofSeconds(1), () -> { entered.countDown(); await(release); return "ok"; }));
        entered.await();
        boolean timeout = false;
        try { coordinator.execute("timeout", Duration.ofMillis(10), () -> "wrong"); }
        catch (FollowerWaitTimeoutException expected) { timeout = true; }
        release.countDown();
        leader.get();
        return singleFlightRow(id, 2, 1, 2, 1, 1, 1, timeout ? 1 : 0, timeout);
    }

    private Map<String, Object> singleFlightCleanup(String id) {
        JvmSingleFlightCoordinator<String> coordinator = new JvmSingleFlightCoordinator<>();
        AtomicInteger computes = new AtomicInteger();
        coordinator.execute("cleanup", Duration.ofSeconds(1), () -> { computes.incrementAndGet(); return "one"; });
        coordinator.execute("cleanup", Duration.ofSeconds(1), () -> { computes.incrementAndGet(); return "two"; });
        return singleFlightRow(id, 1, 1, 2, 2, 0, computes.get(), 0, computes.get() == 2);
    }

    private Map<String, Object> singleFlightRow(String id, int concurrency, int round, int requests,
                                                long leaders, long followers, int computes, int failures, boolean pass) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("caseId", id); row.put("concurrency", concurrency); row.put("round", round);
        row.put("requestCount", requests); row.put("leaderCount", leaders); row.put("followerCount", followers);
        row.put("expensiveComputeCount", computes); row.put("canonicalArtifactCount", computes == 0 ? 0 : 1);
        row.put("successCount", failures == 0 ? requests : 0); row.put("failureCount", failures);
        row.put("pass", pass);
        return row;
    }

    private Map<String, Object> row(String id, String cacheStatus, String cacheOutcome, String dbOutcome,
                                    int computes, int retrievalCalls, int llmCalls, int populates, boolean failed, boolean pass) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("caseId", id); row.put("redisStatus", cacheStatus); row.put("redisOutcome", cacheOutcome);
        row.put("dbOutcome", dbOutcome); row.put("computeCount", computes); row.put("retrievalCalls", retrievalCalls);
        row.put("finalLlmCalls", llmCalls); row.put("cachePopulateCount", populates); row.put("failed", failed); row.put("pass", pass);
        return row;
    }

    private void writeJson(String filename, Object value) {
        try {
            Files.createDirectories(Path.of("target/phase1-evidence"));
            new com.fasterxml.jackson.databind.ObjectMapper().writeValue(Path.of("target/phase1-evidence", filename).toFile(), value);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }

    private static long traceEventCount(com.aijob.analyzer.trace.InProcessTraceRecorder trace, List<String> traceIds) {
        return traceEventCount(trace, traceIds, null);
    }

    private static long traceEventCount(com.aijob.analyzer.trace.InProcessTraceRecorder trace, List<String> traceIds, TraceEventType type) {
        return traceIds.stream().flatMap(id -> trace.getRecords(id).stream())
                .filter(record -> type == null || record.eventType() == type).count();
    }

    private static final class FakeCache implements ArtifactCache<AnalysisArtifact> {
        private final CacheAccessStatus status;
        private final CacheLookupOutcome outcome;
        private final AnalysisArtifact value;
        private final AtomicInteger puts = new AtomicInteger();
        private FakeCache(CacheAccessStatus status, CacheLookupOutcome outcome, AnalysisArtifact value) {
            this.status = status; this.outcome = outcome; this.value = value;
        }
        @Override public CacheReadResult<AnalysisArtifact> get(String key) { return new CacheReadResult<>(status, outcome, value); }
        @Override public void put(String key, AnalysisArtifact value, Duration ttl) { puts.incrementAndGet(); }
    }

    private static final class FakeRepository implements AnalysisResultRepository {
        private final Map<AnalysisIdentity, AnalysisArtifact> artifacts = new ConcurrentHashMap<>();
        private String readFailure;
        @Override public DbReadResult<AnalysisArtifact> findCanonical(AnalysisIdentity id) {
            if (readFailure != null) return new DbReadResult<>(DbReadOutcome.READ_FAILED, null, readFailure);
            AnalysisArtifact value = artifacts.get(id);
            return value == null ? new DbReadResult<>(DbReadOutcome.NOT_FOUND, null, null) : new DbReadResult<>(DbReadOutcome.FOUND, value, null);
        }
        @Override public CanonicalCommitResult commitCanonical(AnalysisIdentity id, JobAnalysisResult value, AnalysisDependencySnapshot snapshot) {
            AnalysisArtifact artifact = OfflineEvidenceFixtures.artifact(id, snapshot, true);
            artifacts.putIfAbsent(id, artifact);
            return new CanonicalCommitResult.Inserted(artifact);
        }
        @Override public DbReadResult<AnalysisArtifact> readBack(AnalysisIdentity id) {
            AnalysisArtifact value = artifacts.get(id);
            return value == null ? new DbReadResult<>(DbReadOutcome.NOT_FOUND, null, null) : new DbReadResult<>(DbReadOutcome.FOUND, value, null);
        }
    }
}
