package com.aijob.analyzer.demo;

import com.aijob.analyzer.AiJobAnalyzerApplication;
import com.aijob.analyzer.llm.port.*;
import com.aijob.analyzer.profile.domain.*;
import com.aijob.analyzer.profile.port.CandidateProfileRepository;
import com.aijob.analyzer.retrieval.ProfileProjectionService;
import com.aijob.analyzer.retrieval.port.EmbeddingClient;
import com.aijob.analyzer.retrieval.port.EmbeddingResponse;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatHttpIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    static ServletWebServerApplicationContext app;
    static final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    static final HttpClient http = HttpClient.newHttpClient();
    static int port;
    static final List<ChatModelRequest> chatRequests = new CopyOnWriteArrayList<>();
    static final AtomicInteger failOnce = new AtomicInteger();
    static volatile CountDownLatch entered, release;
    static JsonNode a, b, rejected;

    @BeforeAll static void start() {
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(AiJobAnalyzerApplication.class, Providers.class)
                .run(arguments());
        port = app.getWebServer().getPort();
    }
    static String[] arguments() {
        return new String[]{"--spring.profiles.active=phase4-chat-test", "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(), "--spring.datasource.username=" + MYSQL.getUsername(),
                "--spring.datasource.password=" + MYSQL.getPassword(), "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379), "--spring.data.redis.password=",
                "--app.retrieval.lucene-root=target/phase4-test-index", "--app.embedding.model=TEST-EMBEDDING",
                "--app.versions.final-analysis.model=TEST-CHAT", "--app.versions.requirement-parser.model=TEST-CHAT",
                "--app.versions.chat.model=TEST-CHAT"};
    }
    @AfterAll static void stop() { if (app != null) app.close(); }
    static synchronized void record(String method, String path, Object request, HttpResponse<String> response) throws Exception {
        var evidence = Path.of("target/phase4-evidence"); Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("http.jsonl"), json.writeValueAsString(Map.of("method", method, "path", path,
                "request", request == null ? Map.of() : request, "status", response.statusCode(), "response", json.readTree(response.body()))) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    static HttpResponse<String> request(String method, String path, Object input) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (input == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input)));
        var result = http.send(builder.build(), HttpResponse.BodyHandlers.ofString()); record(method, path, input, result); return result;
    }
    static JsonNode body(HttpResponse<String> response, int status) throws Exception {
        assertEquals(status, response.statusCode(), response.body()); return json.readTree(response.body());
    }
    static long generation() throws Exception { return body(request("GET", "/api/chat/history", null), 200).path("generation").asLong(); }
    static Map<String,Object> input(JsonNode analysis, String id, String question) throws Exception {
        return new LinkedHashMap<>(Map.of("clientRequestId", id, "jobSnapshotId", analysis.path("jobSnapshotId").asText(),
                "analysisId", analysis.path("id").asText(), "question", question, "expectedGeneration", generation()));
    }
    static JsonNode send(JsonNode analysis, String id, String question) throws Exception {
        return body(request("POST", "/api/chat/messages", input(analysis, id, question)), 200);
    }
    @Test @Order(1) void historyStartsEmpty() throws Exception {
        var result = body(request("GET", "/api/chat/history", null), 200);
        assertEquals(0, result.path("generation").asInt()); assertEquals(0, result.path("turns").size());
    }
    @Test @Order(2) void seedAnalysisAndPassThreeRoundsWithFrozenEvidence() throws Exception {
        var repo = app.getBean(CandidateProfileRepository.class);
        var ref = repo.createBuilding("demo-li-tao", "TEST", "TEST");
        var profile = new CandidateProfile(ref.artifactId(), ref.identity(), "TEST", "TEST", ProfileStatus.BUILDING, List.of(),
                List.of(new ProfileFact("F0001", ref.artifactId(), "SKILL", "Java TEST FACT",
                        new SourceReference("TEST-PDF", "TEST resume", "skills", "Java TEST FACT"))), List.of());
        repo.saveValidatedProfile(profile); app.getBean(ProfileProjectionService.class).buildProjections(profile); repo.markReadyAndPublishCurrent(ref.identity());
        a = analysis("JOB_A_MARKER"); b = analysis("JOB_B_MARKER");
        for (int i = 1; i <= 3; i++) {
            var result = send(a, "round-" + i, "QUESTION_" + i);
            assertEquals("SUCCEEDED", result.path("status").asText());
            assertEquals(result.path("question").path("id"), result.path("reply").path("replyToMessageId"));
        }
        var prompt = chatRequests.getLast().userPrompt();
        assertTrue(prompt.contains("QUESTION_1")); assertTrue(prompt.contains("QUESTION_2")); assertTrue(prompt.contains("ANSWER_TEST"));
        assertTrue(prompt.contains("TEST-PDF")); assertTrue(prompt.contains("Java TEST FACT")); assertTrue(prompt.contains("JOB_A_MARKER"));
        assertFalse(prompt.contains("JOB_B_MARKER")); assertTrue(prompt.length() + chatRequests.getLast().systemPrompt().length() <= 24000);
        Files.writeString(Path.of("target/phase4-evidence/context-third-round.json"), prompt);
    }
    static JsonNode analysis(String marker) throws Exception {
        var job = body(request("POST", "/api/jobs", Map.of("sourcePlatform", "TEST", "sourceJobId", marker, "title", "Java 后端 " + marker,
                "city", "上海", "description", "Java " + marker, "simulated", true)), 201);
        return body(request("POST", "/api/analyses", Map.of("clientRequestId", marker, "jobSnapshotId", job.path("id").asText())), 200);
    }
    @Test @Order(3) void paginationAndIdempotencyUseServerOrder() throws Exception {
        var first = body(request("GET", "/api/chat/history?limit=2", null), 200);
        assertEquals(2, first.path("turns").size()); assertTrue(first.path("hasMore").asBoolean());
        var next = body(request("GET", "/api/chat/history?afterSequence=" + first.path("nextAfterSequence").asLong(), null), 200);
        assertEquals(1, next.path("turns").size());
        int before = chatRequests.size(); var saved = send(a, "round-3", "QUESTION_3");
        assertEquals(before, chatRequests.size());
        body(request("POST", "/api/chat/messages", input(a, "round-3", "CHANGED")), 409);
        assertEquals(saved, body(request("GET", "/api/chat/messages/" + saved.path("id").asText(), null), 200));
    }
    @Test @Order(4) void tabIsolationAndExplicitComparison() throws Exception {
        send(b, "tab-b", "B_QUESTION");
        assertFalse(chatRequests.getLast().userPrompt().contains("QUESTION_1"));
        send(a, "tab-a", "A_AFTER_B"); assertFalse(chatRequests.getLast().userPrompt().contains("B_QUESTION"));
        var compare = input(a, "compare", "Compare both analyses"); compare.put("comparisonAnalysisIds", List.of(b.path("id").asText()));
        body(request("POST", "/api/chat/messages", compare), 200);
        assertTrue(chatRequests.getLast().userPrompt().contains("JOB_B_MARKER"));
        send(a, "after-compare", "A_ONLY_AGAIN");
        assertFalse(chatRequests.getLast().userPrompt().contains("JOB_B_MARKER"));
        var mismatched = input(a, "mismatch", "bad"); mismatched.put("jobSnapshotId", b.path("jobSnapshotId").asText());
        body(request("POST", "/api/chat/messages", mismatched), 409);
    }
    @Test @Order(5) void modelFailureAndExplicitRetryKeepOneOperation() throws Exception {
        var failed = send(a, "fail-once", "FAIL_ONCE"); assertEquals("FAILED", failed.path("status").asText());
        var recovered = send(a, "fail-once", "FAIL_ONCE"); assertEquals("SUCCEEDED", recovered.path("status").asText());
        assertEquals(failed.path("id"), recovered.path("id")); assertEquals(failed.path("question"), recovered.path("question"));
        var invalid = send(a, "unknown-ref", "UNKNOWN_REF"); assertEquals("FAILED", invalid.path("status").asText());
        assertTrue(invalid.path("reply").isNull());
    }
    @Test @Order(5) void boundedHistoryDropsOldestTurnsAndInvalidInputsDoNotCallModel() throws Exception {
        for (int i = 1; i <= 8; i++) send(a, "window-" + i, "WINDOW_" + i);
        var latest = chatRequests.getLast();
        assertFalse(latest.userPrompt().contains("QUESTION_1")); assertFalse(latest.userPrompt().contains("WINDOW_1"));
        var policy = send(a, "window-9", "WINDOW_9").path("contextPolicy");
        assertEquals(6, policy.path("historyTurns").asInt()); assertTrue(policy.path("omittedHistoryTurns").asInt() > 0);
        int before = chatRequests.size();
        var missing = input(a, "missing", "missing"); missing.put("analysisId", UUID.randomUUID().toString());
        body(request("POST", "/api/chat/messages", missing), 404);
        body(request("POST", "/api/chat/messages", input(a, "too-long", "x".repeat(2001))), 400);
        body(request("GET", "/api/chat/history?limit=0", null), 400);
        var failed = analysis("FAIL_ANALYSIS_CASE");
        assertEquals("FAILED", failed.path("status").asText());
        body(request("POST", "/api/chat/messages", input(failed, "failed-analysis", "Explain")), 409);
        assertEquals(before, chatRequests.size());
    }
    @Test @Order(6) void clearGenerationPreventsLateReplyAndNewContextLeak() throws Exception {
        entered = new CountDownLatch(1); release = new CountDownLatch(1);
        var input = input(a, "blocked", "BLOCK_CHAT");
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> body(request("POST", "/api/chat/messages", input), 200));
            assertTrue(entered.await(15, TimeUnit.SECONDS));
            body(request("POST", "/api/chat/messages", input(a, "busy", "BUSY")), 409);
            var running = body(request("POST", "/api/chat/messages", input), 202);
            assertEquals("RUNNING", running.path("status").asText());
            body(request("DELETE", "/api/chat", Map.of("expectedGeneration", generation())), 200);
            var fresh = send(b, "new-generation", "AFTER_CLEAR"); assertEquals("SUCCEEDED", fresh.path("status").asText());
            assertFalse(chatRequests.getLast().userPrompt().contains("B_QUESTION")); assertFalse(chatRequests.getLast().userPrompt().contains("QUESTION_1"));
            release.countDown(); var late = pending.get(15, TimeUnit.SECONDS);
            assertEquals("CLEARED", late.path("status").asText()); assertTrue(late.path("reply").isNull());
            var history = body(request("GET", "/api/chat/history", null), 200);
            assertEquals(1, history.path("turns").size());
            var jdbc = app.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM demo_chat_operation WHERE generation=? AND (request_json IS NOT NULL OR context_json IS NOT NULL OR answer_json IS NOT NULL)", Integer.class, input.get("expectedGeneration")));
            body(request("GET", "/api/chat/history?expectedGeneration=" + input.get("expectedGeneration"), null), 409);
            assertEquals(a.path("execution"), body(request("GET", "/api/analyses/" + a.path("id").asText(), null), 200).path("execution"));
            assertEquals("CLEARED", body(request("POST", "/api/chat/messages", input), 200).path("status").asText());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test @Order(7) void staleAnalysesAndFailedAnalysesAreRejectedAndHardResultsExplainable() throws Exception {
        var rules = body(request("GET", "/api/rules/current", null), 200);
        var config = (com.fasterxml.jackson.databind.node.ObjectNode) rules.path("config").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)config.path("criteria")).set("cities", json.valueToTree(List.of("北京")));
        ((com.fasterxml.jackson.databind.node.ObjectNode)config.path("modes")).put("city", "HARD");
        body(request("PUT", "/api/rules", Map.of("expectedVersion", 0, "config", config)), 200);
        body(request("POST", "/api/chat/messages", input(a, "stale", "stale")), 409);
        rejected = analysis("RULE_TERMINAL_TEST");
        assertEquals("RULE_TERMINAL", rejected.path("execution").path("resultOrigin").asText());
        assertEquals("SUCCEEDED", send(rejected, "explain-rule", "Explain rejection").path("status").asText());
        assertTrue(chatRequests.getLast().userPrompt().contains("UNSATISFIED"));
        var compare = input(rejected, "compare-stale", "Compare"); compare.put("comparisonAnalysisIds", List.of(a.path("id").asText()));
        body(request("POST", "/api/chat/messages", compare), 409);
    }
    @Test @Order(8) void inFlightReplyKeepsOriginalProfileWhileNewSubmissionsRejectStaleAnalysis() throws Exception {
        entered = new CountDownLatch(1); release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> send(rejected, "profile-race", "BLOCK_CHAT"));
            assertTrue(entered.await(15, TimeUnit.SECONDS));
            var repo = app.getBean(CandidateProfileRepository.class);
            var ref = repo.createBuilding("demo-li-tao", "TEST-NEW", "TEST");
            repo.saveValidatedProfile(new CandidateProfile(ref.artifactId(), ref.identity(), "TEST-NEW", "TEST", ProfileStatus.BUILDING,
                    List.of(), List.of(), List.of()));
            repo.markReadyAndPublishCurrent(ref.identity());
            release.countDown(); var completed = pending.get(15, TimeUnit.SECONDS);
            assertEquals(1, completed.path("reply").path("bindings").get(0).path("dependencies").path("profileArtifactIdentity").path("profileVersion").asInt());
            body(request("POST", "/api/chat/messages", input(rejected, "new-profile-stale", "NEW")), 409);
            rejected = analysis("NEW_PROFILE_RULE_TERMINAL");
        } finally {release.countDown(); executor.shutdownNow();}
    }
    @Test @Order(9) void independentJvmRecoversInterruptedStateAndHistory() throws Exception {
        var saved = send(rejected, "restart", "PERSIST_ME");
        var jdbc = app.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        jdbc.update("UPDATE demo_chat_operation SET status='RUNNING',answer_json=NULL WHERE id=?", saved.path("id").asText());
        jdbc.update("UPDATE demo_chat_state SET active_request_id=?", saved.path("id").asText());
        app.close(); app = null;
        var evidence = Path.of("target/phase4-evidence").toAbsolutePath(); var portFile = evidence.resolve("restart.port"); Files.deleteIfExists(portFile);
        var command = new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dphase4.portFile=" + portFile, "-cp", System.getProperty("java.class.path"), ChatHttpTestServer.class.getName()));
        command.addAll(List.of(arguments()));
        var child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(evidence.resolve("restart.log").toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            while (child.isAlive() && !Files.exists(portFile) && System.nanoTime() < deadline) Thread.sleep(100);
            assertTrue(Files.exists(portFile)); port = Integer.parseInt(Files.readString(portFile));
            var restored = body(request("GET", "/api/chat/messages/" + saved.path("id").asText(), null), 200);
            assertEquals("INTERRUPTED", restored.path("status").asText());
            assertEquals("SUCCEEDED", send(rejected, "restart", "PERSIST_ME").path("status").asText());
            assertTrue(body(request("GET", "/api/chat/history", null), 200).path("turns").size() >= 2);
        } finally { child.destroy(); assertTrue(child.waitFor(20, TimeUnit.SECONDS)); }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Providers {
        @Bean @Primary ChatModelClient testChatProvider() {
            return req -> {
                if (req.systemPrompt().contains("Chat Answer Engine")) {
                    chatRequests.add(req);
                    if (req.userPrompt().contains("\"question\":\"BLOCK_CHAT\"")) {
                        entered.countDown(); try { release.await(20, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
                    }
                    if (req.userPrompt().contains("\"question\":\"FAIL_ONCE\"") && failOnce.getAndIncrement() == 0) throw new com.aijob.analyzer.llm.error.ProviderTransportException("TEST timeout", new java.net.SocketTimeoutException());
                    return response(req.userPrompt().contains("\"question\":\"UNKNOWN_REF\"") ? "{\"answer\":\"TEST\",\"evidenceRefs\":[\"UNKNOWN\"]}" : "{\"answer\":\"ANSWER_TEST\",\"evidenceRefs\":[]}");
                }
                if (req.systemPrompt().contains("Requirement Parser")) return response("{\"structuredConstraints\":[],\"retrievalRequirements\":[{\"requirementType\":\"SKILL\",\"normalizedText\":\"Java\",\"priority\":\"REQUIRED\",\"sourceText\":\"Java\"}],\"ignoredContext\":[]}");
                if (req.userPrompt().contains("FAIL_ANALYSIS_CASE")) return response("{\"decision\":\"INVALID\"}");
                return response("{\"decision\":\"CONSIDER\",\"overallAssessment\":\"TEST\",\"requirementAssessments\":[{\"requirementId\":\"R0001\",\"status\":\"SUPPORTED\",\"evidenceRefs\":[\"F0001\"],\"reasoning\":\"TEST\",\"gap\":\"\"}],\"risks\":[],\"strengths\":[],\"recommendation\":\"TEST\",\"summary\":\"TEST\"}");
            };
        }
        static ChatModelResponse response(String text) { return new ChatModelResponse(text, "TEST", "TEST", "TEST", 1, 1, "stop"); }
        @Bean @Primary EmbeddingClient testEmbedding() { return (model, text) -> new EmbeddingResponse(new float[]{1,0,0}, model, "TEST", 1); }
    }
}
