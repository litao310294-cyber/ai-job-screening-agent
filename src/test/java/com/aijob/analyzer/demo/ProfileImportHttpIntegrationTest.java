package com.aijob.analyzer.demo;

import com.aijob.analyzer.AiJobAnalyzerApplication;
import com.aijob.analyzer.llm.port.*;
import com.aijob.analyzer.retrieval.port.*;
import com.aijob.analyzer.demo.profile.ResumeImportService;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProfileImportHttpIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withCommand("--max-allowed-packet=64M");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    static ServletWebServerApplicationContext app;
    static int port;
    static String importId;
    static long publishedVersion;
    static byte[] firstPdf;
    static final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    static final HttpClient http = HttpClient.newHttpClient();
    static final AtomicInteger parses = new AtomicInteger();
    static final AtomicInteger embeddings = new AtomicInteger();
    static final AtomicBoolean failParse = new AtomicBoolean();
    static final AtomicBoolean failEmbedding = new AtomicBoolean();

    @BeforeAll static void start() throws Exception {
        Files.createDirectories(Path.of("target/phase2a-evidence"));
        firstPdf = PdfResumeExtractorTest.pdf("Java backend resume", false);
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(AiJobAnalyzerApplication.class, TestProviders.class).run(arguments());
        port = app.getWebServer().getPort();
    }
    static String[] arguments() {
        return new String[]{"--server.port=0", "--spring.profiles.active=phase2a-test", "--app.demo.provider-mode=TEST",
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(), "--spring.datasource.username=" + MYSQL.getUsername(),
                "--spring.datasource.password=" + MYSQL.getPassword(), "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379), "--spring.data.redis.password=",
                "--app.retrieval.lucene-root=target/phase2a-test-index", "--app.embedding.model=TEST-EMBEDDING",
                "--app.versions.resume-parser.model=TEST-CHAT"};
    }
    @AfterAll static void stop() { if(app != null) app.close(); }
    static JsonNode request(String method, String path, Object value, int status) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (value == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(value)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), response.body());
        return json.readTree(response.body());
    }
    static JsonNode upload(byte[] bytes) throws Exception {
        String boundary = "phase2a-test-boundary";
        byte[] prefix = ("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"TEST.pdf\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes();
        byte[] suffix = ("\r\n--"+boundary+"--\r\n").getBytes();
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/profile/resume/upload"))
                .header("Content-Type", "multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArrays(List.of(prefix,bytes,suffix)));
        var response = http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        assertTrue(Set.of(200,202).contains(response.statusCode()),response.body());
        return json.readTree(response.body());
    }
    static String route(String id, String action) { return "/api/profile/imports/"+id+action; }
    static JsonNode current() throws Exception { return request("GET","/api/profile/current",null,200); }
    static JsonNode confirmed(String id, long revision) throws Exception {
        return request("POST",route(id,"/confirm"),Map.of("expectedRevision",revision,"confirmed",true),200);
    }
    @Test @Order(1) void uploadConcurrencyAndStructureOnce() throws Exception {
        var executor = Executors.newFixedThreadPool(6);
        try {
            var futures = new ArrayList<Future<JsonNode>>();
            for(int i=0;i<6;i++) futures.add(executor.submit(() -> upload(firstPdf)));
            var ids = new HashSet<String>();
            for(var future:futures) ids.add(future.get().path("id").asText());
            assertEquals(1,ids.size()); importId=ids.iterator().next();
            assertEquals("TEXT_READY",request("GET",route(importId,""),null,200).path("status").asText());
            request("GET","/api/profile/current",null,409);
            var structures = new ArrayList<Future<HttpResponse<String>>>();
            for(int i=0;i<6;i++) structures.add(executor.submit(() -> http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+route(importId,"/structure"))).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString())));
            for(var future:structures) assertTrue(Set.of(200,202).contains(future.get().statusCode()));
            assertEquals(1,parses.get());
            assertEquals("AWAITING_CONFIRMATION",request("GET",route(importId,""),null,200).path("status").asText());
            request("GET","/api/profile/current",null,409);
        } finally { executor.shutdownNow(); }
    }
    @Test @Order(2) void correctionConfirmationAndDeduplication() throws Exception {
        var data=request("GET",route(importId,"/facts"),null,200);
        var draft=data.path("facts").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)draft.path("retrievalFacts").get(0)).put("normalizedText","Java backend corrected by test");
        var edited=request("PUT",route(importId,"/facts"),Map.of("expectedRevision",1,"facts",draft),200);
        assertEquals(2,edited.path("revision").asInt());
        request("PUT",route(importId,"/facts"),Map.of("expectedRevision",1,"facts",draft),409);
        request("POST",route(importId,"/confirm"),Map.of("expectedRevision",2,"confirmed",false),400);
        var ready=confirmed(importId,2);
        assertEquals("READY",ready.path("status").asText());
        assertEquals("TEST",ready.path("providerMode").asText());
        publishedVersion=ready.path("profileArtifactId").asLong();
        int before=embeddings.get();
        assertEquals(importId,upload(firstPdf).path("id").asText());
        assertEquals("READY",confirmed(importId,2).path("status").asText());
        assertEquals(before,embeddings.get());
        request("PUT",route(importId,"/facts"),Map.of("expectedRevision",2,"facts",draft),409);
        assertEquals(1,current().path("persistedVectorCount").asInt());
        var hits=request("GET","/api/profile/search?query=Java",null,200);
        assertEquals(1,hits.path("keywordHits").size());
        assertEquals(1,hits.path("vectorHits").size());
    }
    @Test @Order(3) void failedReplacementRetainsOldProfileAndRetries() throws Exception {
        var next=upload(PdfResumeExtractorTest.pdf("Java replacement resume",false));
        String id=next.path("id").asText();
        failParse.set(true);
        assertEquals("FAILED_STRUCTURE",request("POST",route(id,"/structure"),null,200).path("status").asText());
        assertEquals(publishedVersion,current().path("profile").path("artifactId").asLong());
        failParse.set(false);
        var structured=request("POST",route(id,"/retry"),null,200);
        assertEquals("AWAITING_CONFIRMATION",structured.path("status").asText());
        failEmbedding.set(true);
        assertEquals("FAILED_INDEX",confirmed(id,1).path("status").asText());
        assertEquals(publishedVersion,current().path("profile").path("artifactId").asLong());
        failEmbedding.set(false);
        var ready=request("POST",route(id,"/retry"),null,200);
        assertEquals("READY",ready.path("status").asText());
        assertTrue(ready.path("profileArtifactId").asLong()>publishedVersion);
        publishedVersion=ready.path("profileArtifactId").asLong();
        var hits=request("GET","/api/profile/search?query=Java",null,200);
        assertEquals(1,hits.path("keywordHits").size());
        assertEquals(publishedVersion,current().path("profile").path("artifactId").asLong());
    }
    @Test @Order(4) void invalidPdfAndTextRemediationPreserveSource() throws Exception {
        for(var bytes:List.of("%PDF broken".getBytes(),PdfResumeExtractorTest.pdf(null,false),PdfResumeExtractorTest.pdf("Java",true))) {
            var failed=upload(bytes);
            assertEquals("FAILED_EXTRACTION",failed.path("status").asText());
            assertEquals(publishedVersion,current().path("profile").path("artifactId").asLong());
            request("POST",route(failed.path("id").asText(),"/retry"),null,200);
        }
        var original=upload("%PDF damaged".getBytes());
        var text=request("POST","/api/profile/imports/text",Map.of("fileName","TEST remedied text","text","Java manual resume text","originalImportId",original.path("id").asText()),200);
        assertEquals("USER_TEXT_REMEDIATION",text.path("sourceKind").asText());
        assertEquals(original.path("id"),text.path("originalImportId"));
    }
    @Test @Order(5) void specifiedPdfActuallyExtractsWithoutCallingProvider() throws Exception {
        int before=parses.get(); byte[] bytes=SyntheticResumeFixture.suppliedOrSynthetic();
        var view=upload(bytes);
        assertEquals("TEXT_READY",view.path("status").asText());
        assertEquals(ResumeImportService.sha256(bytes),view.path("contentSha256").asText());
        assertTrue(view.path("textLength").asInt()>1000);
        assertEquals(before,parses.get());
        Files.writeString(Path.of("target/phase2a-evidence/specified-pdf-test.json"),json.writeValueAsString(view));
    }
    @Test @Order(6) void independentJvmRestoresBothRetrievers() throws Exception {
        app.close(); app=null;
        var directory=Path.of("target/phase2a-evidence").toAbsolutePath();
        var portFile=directory.resolve("restart.port"); Files.deleteIfExists(portFile);
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-Dphase2a.portFile="+portFile,"-cp",System.getProperty("java.class.path"),ProfileImportTestServer.class.getName()));
        command.addAll(List.of(arguments()));
        var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve("restart.log").toFile()).start();
        try {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
            while(process.isAlive()&&!Files.exists(portFile)&&System.nanoTime()<until) Thread.sleep(100);
            assertTrue(Files.exists(portFile),"See restart.log"); port=Integer.parseInt(Files.readString(portFile));
            assertEquals(importId, upload(firstPdf).path("id").asText());
            assertEquals(publishedVersion,current().path("profile").path("artifactId").asLong());
            var hits=request("GET","/api/profile/search?query=Java",null,200);
            assertEquals(1,hits.path("keywordHits").size()); assertEquals(1,hits.path("vectorHits").size());
            Files.writeString(directory.resolve("restart-search.json"),json.writeValueAsString(hits));
        } finally { process.destroy(); assertTrue(process.waitFor(20,TimeUnit.SECONDS)); }
        long calls=Files.readAllLines(directory.resolve("restart.log")).stream().filter(line->line.contains("TEST EMBEDDING CALL")).count();
        assertEquals(1,calls);
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class TestProviders {
        @Bean @Primary ChatModelClient testResumeProvider() {
            return req -> {
                parses.incrementAndGet();
                String response=failParse.get()?"{\"retrievalFacts\":\"bad\"}":"{\"structuredFacts\":[],\"retrievalFacts\":[{\"factType\":\"SKILL\",\"normalizedText\":\"Java backend\",\"source\":{\"sourceId\":\"TEST\",\"sourceTitle\":\"TEST\",\"sourceSection\":\"skills\",\"sourceText\":\"Java\"}}],\"contextOnly\":[]}";
                return new ChatModelResponse(response,"TEST","TEST-CHAT",null,1,1,"stop");
            };
        }
        @Bean @Primary EmbeddingClient testEmbeddingProvider() {
            return (model,text) -> {
                embeddings.incrementAndGet();
                System.out.println("TEST EMBEDDING CALL");
                if(failEmbedding.get()) throw new IllegalStateException("TEST embedding failure");
                return new EmbeddingResponse(new float[]{1,0},"TEST-EMBEDDING",null,1);
            };
        }
    }
}
