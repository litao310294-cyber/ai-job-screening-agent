package com.aijob.analyzer.demo;

import com.aijob.analyzer.AiJobAnalyzerApplication;
import com.aijob.analyzer.llm.port.*;
import com.aijob.analyzer.llm.error.ProviderError;
import com.aijob.analyzer.profile.port.CandidateProfileRepository;
import com.aijob.analyzer.profile.domain.*;
import com.aijob.analyzer.retrieval.ProfileProjectionService;
import com.aijob.analyzer.retrieval.port.*;
import com.aijob.analyzer.workflow.port.RuleCheckService;
import com.aijob.analyzer.workflow.domain.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP, MySQL and Redis, with explicitly TEST-ONLY model and rule providers. */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoHttpIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    static ServletWebServerApplicationContext app;
    static int port;
    static final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    static final HttpClient http = HttpClient.newHttpClient();
    static String historyId;
    static final AtomicInteger calls = new AtomicInteger();
    static final AtomicInteger timeoutCalls = new AtomicInteger();
    static volatile ChatModelRequest lastRequirement;
    static volatile ChatModelRequest lastFinal;
    static volatile CountDownLatch blocked;
    static volatile CountDownLatch release;

    @BeforeAll static void start() { boot(); }
    static void boot() {
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(AiJobAnalyzerApplication.class, TestProviders.class)
                .properties("spring.profiles.active=phase1-http-test")
                .run("--server.port=0", "--server.address=127.0.0.1",
                     "--spring.datasource.url=" + MYSQL.getJdbcUrl(), "--spring.datasource.username=" + MYSQL.getUsername(),
                     "--spring.datasource.password=" + MYSQL.getPassword(), "--spring.data.redis.host=" + REDIS.getHost(),
                     "--spring.data.redis.port=" + REDIS.getMappedPort(6379), "--spring.data.redis.password=",
                     "--app.retrieval.lucene-root=target/phase1-test-index", "--app.embedding.model=TEST-EMBEDDING",
                     "--app.versions.final-analysis.model=TEST-CHAT", "--app.versions.requirement-parser.model=TEST-CHAT");
        port = app.getWebServer().getPort();
    }
    @AfterAll static void stop() { if (app != null) app.close(); }
    static HttpResponse<String> request(String method, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        saveExchange(method, path, body, response);
        return response;
    }
    static synchronized void saveExchange(String method, String path, Object body, HttpResponse<String> response) throws Exception {
        var directory = java.nio.file.Path.of("target/phase1-evidence");
        java.nio.file.Files.createDirectories(directory);
        var exchange = new LinkedHashMap<String,Object>();
        exchange.put("method", method); exchange.put("path", path); exchange.put("request", body);
        exchange.put("status", response.statusCode()); exchange.put("response", json.readTree(response.body()));
        java.nio.file.Files.writeString(directory.resolve("http-exchanges.jsonl"), json.writeValueAsString(exchange) + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
    static JsonNode body(HttpResponse<String> response, int expected) throws Exception {
        assertEquals(expected, response.statusCode(), response.body());
        return json.readTree(response.body());
    }
    static JsonNode job(String suffix) throws Exception {
        return body(request("POST", "/api/jobs", Map.of("sourcePlatform", "TEST", "sourceJobId", "fixed-" + suffix,
                "title", "Java " + suffix, "description", "Java backend " + suffix, "simulated", true)), 201);
    }
    static JsonNode analyze(String jobId, String requestId) throws Exception {
        return body(request("POST", "/api/analyses", Map.of("jobSnapshotId", jobId, "clientRequestId", requestId)), 200);
    }
    static void seedProfile() {
        var repo = app.getBean(CandidateProfileRepository.class);
        var ref = repo.createBuilding("demo-li-tao", "test-resume", "test-parser");
        var src = new SourceReference("TEST-FIXTURE", "TEST profile", "skills", "Java backend");
        var p = new CandidateProfile(ref.artifactId(), ref.identity(), "test-resume", "test-parser", ProfileStatus.BUILDING,
                List.of(), List.of(new ProfileFact("F0001", ref.artifactId(), "SKILL", "Java backend", src)), List.of());
        repo.saveValidatedProfile(p);
        assertEquals("SUCCESS", app.getBean(ProfileProjectionService.class).buildProjections(p).name());
        repo.markReadyAndPublishCurrent(ref.identity());
    }
    @Test @Order(1) void healthSnapshotAndMissingProfile() throws Exception {
        assertEquals("UP", body(request("GET", "/api/health", null), 200).path("status").asText());
        assertEquals("demo-li-tao", body(request("GET", "/api/session", null), 200).path("userId").asText());
        var j = job("initial");
        assertTrue(j.path("job").path("simulated").asBoolean());
        assertEquals(j, body(request("GET", "/api/jobs/" + j.path("id").asText(), null), 200));
        assertEquals("PROFILE_NOT_READY", body(request("POST", "/api/analyses", Map.of("jobSnapshotId", j.path("id").asText(), "clientRequestId", "no-profile")), 409).path("code").asText());
        seedProfile();
    }
    @Test @Order(2) void computeReplayConflictAndCacheSources() throws Exception {
        var j = job("cache"); String id = j.path("id").asText();
        var first = analyze(id, "cache-1"); historyId = first.path("id").asText();
        assertEquals("SUCCESS", first.path("status").asText(), first.toPrettyString());
        assertEquals("COMPUTED", first.path("reuseSource").asText());
        var evidence = body(request("GET", "/api/analyses/" + first.path("id").asText() + "/evidence", null), 200);
        assertEquals(first.path("dependencies").path("profileArtifactIdentity"), evidence.path("profileIdentity"));
        assertEquals("F0001", evidence.path("facts").get(0).path("factId").asText());
        assertEquals("RECOMMEND_APPLY", first.path("execution").path("result").path("decision").asText());
        int count = calls.get();
        assertEquals(first, analyze(id, "cache-1")); assertEquals(count, calls.get());
        assertEquals(409, request("POST", "/api/analyses", Map.of("jobSnapshotId", job("other").path("id").asText(), "clientRequestId", "cache-1")).statusCode());
        assertEquals("REDIS", analyze(id, "cache-2").path("reuseSource").asText());
        var redis = app.getBean(StringRedisTemplate.class);
        var keys = redis.keys("*"); if (keys != null && !keys.isEmpty()) redis.delete(keys);
        assertEquals("DATABASE", analyze(id, "cache-3").path("reuseSource").asText());
        assertEquals(count, calls.get());
        var trace = body(request("GET", "/api/analyses/" + historyId + "/trace", null), 200);
        assertTrue(trace.size() > 0); long last = 0;
        for (var record : trace) {
            assertEquals(first.path("taskId"), record.path("taskId"));
            assertEquals(first.path("traceId"), record.path("traceId"));
            assertTrue(record.path("sequence").asLong() > last); last = record.path("sequence").asLong();
        }
    }
    @Test @Order(3) void transportTimeoutCanRetryAndPersistSuccess() throws Exception {
        timeoutCalls.set(0);
        var result = analyze(job("TIMEOUT_ONCE_TEST").path("id").asText(), "timeout-once");
        assertEquals("SUCCESS", result.path("status").asText(), result.toPrettyString());
        assertEquals("LLM_ANALYSIS", result.path("execution").path("resultOrigin").asText());
        assertEquals(2, timeoutCalls.get());
        assertEquals(result, body(request("GET", "/api/analyses/" + result.path("id").asText(), null), 200));
    }
    @Test @Order(3) void terminalStatesAreDurableWithoutCanonical() throws Exception {
        for (String mode : List.of("FAIL_SCHEMA", "DEGRADE_TEST", "TEST_REJECT")) {
            var result = analyze(job(mode).path("id").asText(), mode);
            assertEquals(mode.equals("FAIL_SCHEMA") ? "FAILED" : mode.equals("DEGRADE_TEST") ? "DEGRADED" : "SUCCESS", result.path("status").asText(), result.toPrettyString());
            assertTrue(result.path("canonicalArtifactId").isNull());
            assertEquals(result, body(request("GET", "/api/analyses/" + result.path("id").asText(), null), 200));
        }
    }
    @Test @Order(2) void legacyRequestAndNullableRuleColumnsRemainReadable() throws Exception {
        String jobId=job("LEGACY_COMPAT").path("id").asText();
        var original=analyze(jobId,"legacy-seed");
        String id=UUID.randomUUID().toString();
        String payload="{\"clientRequestId\":\"legacy-existing\",\"jobSnapshotId\":\""+jobId+"\",\"expectedProfileVersion\":null}";
        String fingerprint=com.aijob.analyzer.demo.profile.ResumeImportService.sha256(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        app.getBean(JdbcTemplate.class).update("INSERT INTO demo_analysis_operation(id,user_id,client_request_id,request_fingerprint,job_snapshot_id,task_id,trace_id,status,dependency_json,outcome_json) SELECT ?,user_id,'legacy-existing',?,job_snapshot_id,?,?,status,JSON_SET(dependency_json,'$.ruleVersion','RULE-V1'),outcome_json FROM demo_analysis_operation WHERE id=?",
                id,fingerprint,UUID.randomUUID().toString(),UUID.randomUUID().toString(),original.path("id").asText());
        int before=calls.get();
        var restored=analyze(jobId,"legacy-existing");
        assertEquals(id,restored.path("id").asText());
        assertTrue(restored.path("rules").isNull());
        assertTrue(restored.path("stale").asBoolean());
        assertEquals(before,calls.get());
    }
    @Test @Order(4) void restartRestoresHistoryAndTrace() throws Exception {
        var before = body(request("GET", "/api/analyses/" + historyId, null), 200);
        var trace = body(request("GET", "/api/analyses/" + historyId + "/trace", null), 200);
        String interrupted = UUID.randomUUID().toString();
        app.getBean(JdbcTemplate.class).update("INSERT INTO demo_analysis_operation(id,user_id,client_request_id,request_fingerprint,job_snapshot_id,task_id,trace_id,status,dependency_json) SELECT ?,user_id,?,request_fingerprint,job_snapshot_id,?,?,'RUNNING',dependency_json FROM demo_analysis_operation WHERE id=?",
                interrupted, "interrupted-request", UUID.randomUUID().toString(), UUID.randomUUID().toString(), historyId);
        app.close(); boot();
        assertEquals(before, body(request("GET", "/api/analyses/" + historyId, null), 200));
        assertEquals(trace, body(request("GET", "/api/analyses/" + historyId + "/trace", null), 200));
        var recovered = body(request("GET", "/api/analyses/" + interrupted, null), 200);
        assertEquals("FAILED", recovered.path("status").asText());
        assertEquals("PROCESS_INTERRUPTED", recovered.path("errorCode").asText());
    }

    @Test @Order(5) void concurrentReplayFreezesProfileAndRejectsStaleVersion() throws Exception {
        String id = job("BLOCK_TEST").path("id").asText();
        blocked = new CountDownLatch(1); release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> analyze(id, "concurrent-1"));
            assertTrue(blocked.await(15, TimeUnit.SECONDS));
            var running = body(request("POST", "/api/analyses", Map.of("jobSnapshotId", id, "clientRequestId", "concurrent-1")), 202);
            long version = running.path("dependencies").path("profileArtifactIdentity").path("profileVersion").asLong();
            assertEquals(409, request("POST", "/api/analyses", Map.of("jobSnapshotId", id, "clientRequestId", "concurrent-1", "expectedProfileVersion", version)).statusCode());
            seedProfile();
            release.countDown();
            var completed = future.get(15, TimeUnit.SECONDS);
            assertEquals(running.path("id"), completed.path("id"));
            assertEquals("SUCCESS", completed.path("status").asText(), completed.toPrettyString());
            assertEquals(version, completed.path("dependencies").path("profileArtifactIdentity").path("profileVersion").asLong());
            var oldEvidence = body(request("GET", "/api/analyses/" + completed.path("id").asText() + "/evidence", null), 200);
            assertEquals(version, oldEvidence.path("profileIdentity").path("profileVersion").asLong());
            assertEquals(409, request("POST", "/api/analyses", Map.of("jobSnapshotId", id, "clientRequestId", "stale-version", "expectedProfileVersion", version)).statusCode());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test @Order(6) void traceWriteFailureDoesNotChangeSuccessfulBusinessResult() throws Exception {
        var jdbc = app.getBean(JdbcTemplate.class);
        jdbc.execute("RENAME TABLE demo_trace_record TO demo_trace_record_unavailable");
        try {
            var result = analyze(job("TRACE_FAILURE").path("id").asText(), "trace-failure");
            assertEquals("SUCCESS", result.path("status").asText(), result.toPrettyString());
            assertTrue(result.path("canonicalArtifactId").asLong() > 0);
        } finally { jdbc.execute("RENAME TABLE demo_trace_record_unavailable TO demo_trace_record"); }
    }
    @Test @Order(7) void normalizationValidationAndOriginBoundary() throws Exception {
        var input = new LinkedHashMap<String,Object>();
        input.put("sourcePlatform", "TEST"); input.put("title", "  Java\t backend ");
        input.put("description", "Java\r\nbackend"); input.put("simulated", true);
        var first = body(request("POST", "/api/jobs", input), 201);
        assertEquals("Java\r\nbackend",first.path("job").path("description").asText());
        input.put("title", "Java backend"); input.put("description", "Java\nbackend");
        var second = body(request("POST", "/api/jobs", input), 201);
        assertNotEquals(first.path("id"), second.path("id"));
        assertEquals(first.path("fingerprint"), second.path("fingerprint"));
        input.put("company", "Different company");
        assertNotEquals(first.path("fingerprint"), body(request("POST", "/api/jobs", input), 201).path("fingerprint"));
        input.put("internshipDays", 0);
        assertEquals(400, request("POST", "/api/jobs", input).statusCode());
        var forbidden = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.getWebServer().getPort() + "/api/jobs"))
                .header("Origin", "https://www.zhipin.com").GET().build();
        assertEquals(403, http.send(forbidden, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test @Order(8) void fixedFixturesHaveStableIdsAndFingerprints() throws Exception {
        var fixtures = json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("docs/demo/fixtures/jobs.json")));
        var ids = new HashSet<String>();
        for (var sample : fixtures.path("cases")) {
            assertTrue(ids.add(sample.path("caseId").asText()));
            var first = body(request("POST", "/api/jobs", sample.path("job")), 201);
            var second = body(request("POST", "/api/jobs", sample.path("job")), 201);
            assertEquals(first.path("fingerprint"), second.path("fingerprint"));
            assertEquals(sample.path("caseId"), first.path("job").path("sourceJobId"));
            assertTrue(first.path("job").path("simulated").asBoolean());
        }
        assertEquals(9, ids.size());
    }

    @Test @Order(9) void actualJvmRestartRetainsExecutionAndTrace() throws Exception {
        app.close(); app = null;
        JsonNode saved;
        JsonNode trace;
        Process first = launchTestServer("process-first");
        try {
            saved = analyze(job("PROCESS_RESTART").path("id").asText(), "process-restart");
            assertEquals("SUCCESS", saved.path("status").asText(), saved.toPrettyString());
            trace = body(request("GET", "/api/analyses/" + saved.path("id").asText() + "/trace", null), 200);
        } finally { first.destroy(); assertTrue(first.waitFor(20, TimeUnit.SECONDS)); }
        Process second = launchTestServer("process-second");
        try {
            assertEquals(saved, body(request("GET", "/api/analyses/" + saved.path("id").asText(), null), 200));
            assertEquals(trace, body(request("GET", "/api/analyses/" + saved.path("id").asText() + "/trace", null), 200));
            assertEquals(saved, analyze(saved.path("jobSnapshotId").asText(), "process-restart"));
        } finally { second.destroy(); assertTrue(second.waitFor(20, TimeUnit.SECONDS)); }
    }
    @Test @Order(8) void versionedRulesDriveWorkflowAndFreeze() throws Exception {
        var fixture=json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("docs/demo/fixtures/rules-v1.json")));
        var jobs=json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("docs/demo/fixtures/jobs.json")));
        var config=fixture.path("config").deepCopy();
        assertEquals(0,body(request("GET","/api/rules/current",null),200).path("version").asInt());
        var invalid=config.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)invalid.path("candidate")).put("availableDaysPerWeek",8);
        body(request("POST","/api/rules/validate",Map.of("expectedVersion",0,"config",invalid)),422);
        assertTrue(body(request("POST","/api/rules/validate",Map.of("expectedVersion",0,"config",config)),200).path("valid").asBoolean());
        assertEquals(0,body(request("GET","/api/rules/current",null),200).path("version").asInt());
        assertEquals(1,body(request("PUT","/api/rules",Map.of("expectedVersion",0,"config",config)),200).path("version").asInt());
        assertEquals("rules-2b-v2", body(request("GET", "/api/rules/current", null), 200).path("algorithmVersion").asText());
        body(request("PUT","/api/rules",Map.of("expectedVersion",0,"config",config)),409);
        String selectedJob=null;
        String historical=null;
        for(var expected:fixture.path("cases")) {
            JsonNode jobInput=null;
            for(var sample:jobs.path("cases")) if(sample.path("caseId").equals(expected.path("jobId"))) jobInput=sample.path("job");
            assertNotNull(jobInput);
            String id=body(request("POST","/api/jobs",jobInput),201).path("id").asText();
            int before=calls.get();
            int canonical=app.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM analysis_canonical_result",Integer.class);
            var result=analyze(id,"rules-"+expected.path("jobId").asText());
            assertEquals(1,result.path("rules").path("version").asInt());
            boolean found=false;
            for(var item:result.path("ruleResult").path("items")) if(item.path("field").equals(expected.path("field"))) {
                assertEquals(expected.path("expected").asText(),item.path("verdict").asText(),result.toPrettyString()); found=true;
            }
            assertTrue(found);
            if(expected.path("hardReject").asBoolean()) {
                assertEquals("RULE_TERMINAL",result.path("execution").path("resultOrigin").asText());
                assertEquals(before,calls.get());
                assertEquals(canonical,app.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM analysis_canonical_result",Integer.class));
                var trace=body(request("GET","/api/analyses/"+result.path("id").asText()+"/trace",null),200);
                for(var event:trace) if(event.path("operation").asText().equals("STAGE_STARTED")) {
                    assertEquals("RULE_CHECK",event.path("stage").asText());
                }
            } else assertEquals("LLM_ANALYSIS",result.path("execution").path("resultOrigin").asText(),result.toPrettyString());
            if(expected.path("jobId").asText().equals("SIM-JAVA-A")) {
                selectedJob=id;historical=result.path("id").asText();
                for(String field:List.of("Company: 模拟公司 A","Location: 上海浦东","Education: 本科","Experience: 经验不限","Skills: [Java, Spring Boot]")) {
                    assertTrue(lastRequirement.userPrompt().contains(field),field);
                    assertTrue(lastFinal.userPrompt().contains(field),field);
                }
                assertTrue(lastFinal.userPrompt().contains("candidateValue=5"));
                assertTrue(lastFinal.userPrompt().contains("unit=DAYS_PER_WEEK"));
            }
        }
        verifyConflictingRequirementsThroughHttp(jobs.path("cases").get(0).path("job"));
        verifyPreviousAlgorithmCacheIsNotReused(historical);
        ((com.fasterxml.jackson.databind.node.ObjectNode)config.path("criteria")).set("cities",json.valueToTree(List.of("北京")));
        ((com.fasterxml.jackson.databind.node.ObjectNode)config.path("modes")).put("city","SOFT");
        body(request("PUT","/api/rules",Map.of("expectedVersion",1,"config",config)),200);
        assertTrue(body(request("GET","/api/analyses/"+historical,null),200).path("stale").asBoolean());
        var fresh=analyze(selectedJob,"rules-v2-new");
        assertEquals("COMPUTED",fresh.path("reuseSource").asText());
        assertEquals(2,fresh.path("rules").path("version").asInt());
        body(request("POST","/api/analyses",Map.of("jobSnapshotId",selectedJob,"clientRequestId","rules-stale","expectedRuleVersion",1)),409);
        blocked=new CountDownLatch(1);release=new CountDownLatch(1);
        var executor=Executors.newFixedThreadPool(2);
        try {
            String blocking=job("BLOCK_TEST_RULES").path("id").asText();
            var future=executor.submit(()->analyze(blocking,"rules-blocked"));
            assertTrue(blocked.await(15,TimeUnit.SECONDS));
            body(request("PUT","/api/rules",Map.of("expectedVersion",2,"config",fixture.path("config"))),200);
            release.countDown();
            var completed=future.get(15,TimeUnit.SECONDS);
            assertEquals(2,completed.path("rules").path("version").asInt());
            assertTrue(completed.path("stale").asBoolean());
            var one=executor.submit(()->request("PUT","/api/rules",Map.of("expectedVersion",3,"config",config)));
            var two=executor.submit(()->request("PUT","/api/rules",Map.of("expectedVersion",3,"config",config)));
            assertEquals(Set.of(200,409),Set.of(one.get().statusCode(),two.get().statusCode()));
        } finally {release.countDown();executor.shutdownNow();}
    }
    private void verifyConflictingRequirementsThroughHttp(JsonNode baseJob) throws Exception {
        record ReviewCase(String id, String location, String jd, String field, String expected) {}
        var cases = List.of(
                new ReviewCase("city-conflict", "北京", "工作地点：上海", "city", "UNKNOWN"),
                new ReviewCase("city-unknown", "科技园一号楼", "工作地点：上海", "city", "UNKNOWN"),
                new ReviewCase("city-road", "上海路1号", "工作地点：上海", "city", "UNKNOWN"),
                new ReviewCase("city-jd-conflict", "上海", "工作地点：上海。城市：北京", "city", "UNKNOWN"),
                new ReviewCase("days-conflict", "上海", "每周至少4天；每周至少6天", "attendance", "UNKNOWN"),
                new ReviewCase("months-conflict", "上海", "至少实习3个月；周期9个月", "duration", "UNKNOWN"),
                new ReviewCase("days-repeat", "上海", "每周至少4天；每周4天", "attendance", "SATISFIED"),
                new ReviewCase("months-repeat", "上海", "至少实习3个月；周期3个月", "duration", "SATISFIED"));
        for (var sample : cases) {
            var input = (com.fasterxml.jackson.databind.node.ObjectNode) baseJob.deepCopy();
            input.put("sourceJobId", "REVIEW-" + sample.id());
            input.put("location", sample.location());
            input.put("description", "Java 后端 " + sample.jd());
            input.put("internshipDays", 4);
            input.put("duration", "3个月");
            input.put("internshipMonths", 3);
            String id = body(request("POST", "/api/jobs", input), 201).path("id").asText();
            var result = analyze(id, "review-" + sample.id());
            assertEquals("SUCCESS", result.path("status").asText(), result.toPrettyString());
            assertEquals("LLM_ANALYSIS", result.path("execution").path("resultOrigin").asText());
            boolean found = false;
            for (var item : result.path("ruleResult").path("items")) {
                if (item.path("field").asText().equals(sample.field())) {
                    assertEquals("HARD", item.path("strength").asText());
                    assertEquals(sample.expected(), item.path("verdict").asText());
                    found = true;
                }
            }
            assertTrue(found);
        }
    }
    @SuppressWarnings("unchecked")
    private void verifyPreviousAlgorithmCacheIsNotReused(String historical) throws Exception {
        var sample = body(request("GET", "/api/analyses/" + historical, null), 200);
        var job = job("algorithm-upgrade");
        var jobs = app.getBean(JobSnapshotService.class);
        var prepared = app.getBean(com.aijob.analyzer.analysis.AnalysisApplicationService.class)
                .prepare(DemoModels.USER_ID, jobs.context(jobs.get(job.path("id").asText())));
        var oldNode = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(prepared.snapshot());
        oldNode.put("ruleVersion", prepared.snapshot().ruleVersion().replace("rules-2b-v2:", "rules-2b-v1:"));
        var oldSnapshot = json.treeToValue(oldNode, com.aijob.analyzer.analysis.identity.AnalysisDependencySnapshot.class);
        var oldIdentity = new com.aijob.analyzer.analysis.identity.AnalysisIdentityFactory().create(oldSnapshot);
        assertNotEquals(oldIdentity, prepared.identity());
        var repository = app.getBean(com.aijob.analyzer.analysis.port.AnalysisResultRepository.class);
        repository.commitCanonical(oldIdentity, json.treeToValue(sample.path("execution").path("result"),
                com.aijob.analyzer.analysis.domain.JobAnalysisResult.class), oldSnapshot);
        var oldArtifact = repository.findCanonical(oldIdentity).value();
        assertNotNull(oldArtifact);
        var cache = (com.aijob.analyzer.cache.port.ArtifactCache<com.aijob.analyzer.analysis.domain.AnalysisArtifact>)
                app.getBean(com.aijob.analyzer.cache.port.ArtifactCache.class);
        cache.put(oldIdentity.analysisDependencyFingerprint().value(), oldArtifact, java.time.Duration.ofMinutes(10));
        assertNotNull(cache.get(oldIdentity.analysisDependencyFingerprint().value()).value());
        int before = calls.get();
        var current = analyze(job.path("id").asText(), "review-cache-new-algorithm");
        assertEquals("COMPUTED", current.path("reuseSource").asText());
        assertTrue(calls.get() > before);
        assertNotEquals(oldArtifact.artifactId(), current.path("canonicalArtifactId").asLong());
        assertEquals("REDIS", analyze(job.path("id").asText(), "review-cache-current-redis").path("reuseSource").asText());
        // Clear only the disposable test Redis to force a canonical database read.
        var redis = app.getBean(StringRedisTemplate.class);
        var keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        assertEquals("DATABASE", analyze(job.path("id").asText(), "review-cache-current-database").path("reuseSource").asText());
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/phase2b-review-evidence"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/phase2b-review-evidence/cache-proof.json"),
                json.writeValueAsString(Map.of("oldIdentity", oldIdentity, "currentIdentity", prepared.identity(),
                        "oldCanonicalId", oldArtifact.artifactId(), "current", current, "oldCacheAndDatabaseSeeded", true)));
    }
    static Process launchTestServer(String name) throws Exception {
        var directory = java.nio.file.Path.of("target/phase1-evidence").toAbsolutePath();
        java.nio.file.Files.createDirectories(directory);
        var portFile = directory.resolve(name + ".port");
        java.nio.file.Files.deleteIfExists(portFile);
        var process = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dphase1.portFile=" + portFile, "-cp", System.getProperty("java.class.path"), DemoHttpTestServer.class.getName(),
                "--spring.profiles.active=phase1-http-test", "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(), "--spring.datasource.username=" + MYSQL.getUsername(),
                "--spring.datasource.password=" + MYSQL.getPassword(), "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379), "--spring.data.redis.password=",
                "--app.retrieval.lucene-root=target/phase1-test-index", "--app.embedding.model=TEST-EMBEDDING",
                "--app.versions.final-analysis.model=TEST-CHAT", "--app.versions.requirement-parser.model=TEST-CHAT")
                .redirectErrorStream(true).redirectOutput(directory.resolve(name + ".log").toFile()).start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (process.isAlive() && !java.nio.file.Files.exists(portFile) && System.nanoTime() < deadline) Thread.sleep(100);
        if (!java.nio.file.Files.exists(portFile)) {
            process.destroyForcibly();
            fail("Test server did not start. See " + directory.resolve(name + ".log"));
        }
        port = Integer.parseInt(java.nio.file.Files.readString(portFile));
        return process;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestProviders {
        @Bean @Primary ChatModelClient testChatProvider() {
            return req -> {
                calls.incrementAndGet();
                boolean finalCall = req.systemPrompt().contains("Final Analysis");
                if(finalCall) lastFinal=req; else lastRequirement=req;
                if (finalCall && req.userPrompt().contains("TIMEOUT_ONCE_TEST") && timeoutCalls.getAndIncrement() == 0) {
                    throw new com.aijob.analyzer.llm.error.ProviderTransportException("TEST timeout", new java.net.SocketTimeoutException("TEST timeout"));
                }
                if (finalCall && req.userPrompt().contains("BLOCK_TEST")) {
                    blocked.countDown();
                    try { if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("TEST timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
                if (finalCall && req.userPrompt().contains("FAIL_SCHEMA")) return response("{\"decision\":\"INVALID\"}");
                if (finalCall && req.userPrompt().contains("DEGRADE_TEST")) throw new ProviderError(503, "TEST_UNAVAILABLE", "TEST provider unavailable");
                if (!finalCall) return response("{\"structuredConstraints\":[],\"retrievalRequirements\":[{\"requirementType\":\"SKILL\",\"normalizedText\":\"Java\",\"priority\":\"REQUIRED\",\"sourceText\":\"Java\"}],\"ignoredContext\":[]}");
                return response("{\"decision\":\"RECOMMEND_APPLY\",\"overallAssessment\":\"TEST provider\",\"requirementAssessments\":[{\"requirementId\":\"R0001\",\"status\":\"SUPPORTED\",\"evidenceRefs\":[\"F0001\"],\"reasoning\":\"TEST Java evidence\",\"gap\":\"\"}],\"risks\":[],\"strengths\":[\"TEST Java\"],\"recommendation\":\"TEST apply\",\"summary\":\"TEST ONLY\"}");
            };
        }
        static ChatModelResponse response(String content) { return new ChatModelResponse(content, "TEST", "TEST-CHAT", null, 1, 1, "stop"); }
        @Bean @Primary EmbeddingClient testEmbeddingProvider() { return (model, text) -> new EmbeddingResponse(new float[]{1,0}, "TEST-EMBEDDING", null, 1); }
        @Bean @Primary RuleCheckService testRuleProvider() {
            return new RuleCheckService() {
                @Override public RuleResult check(com.aijob.analyzer.analysis.domain.JobContext job,com.aijob.analyzer.profile.domain.CandidateProfile profile) {
                    return new RuleResult(job.title().contains("TEST_REJECT") ? RuleSignal.HARD_REJECT : RuleSignal.CONTINUE,List.of("TEST ONLY rule provider"));
                }
                @Override public RuleResult check(com.aijob.analyzer.analysis.domain.JobContext job,com.aijob.analyzer.profile.domain.CandidateProfile profile,com.aijob.analyzer.rules.RuleModels.Snapshot rules) {
                    return rules.version()==0?check(job,profile):new com.aijob.analyzer.rules.RulesEngine().check(job,profile,rules);
                }
            };
        }
    }
}
