package com.aijob.analyzer.eval;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.profile.domain.CandidateProfile;
import com.aijob.analyzer.profile.domain.ProfileFact;
import com.aijob.analyzer.profile.domain.ProfileStatus;
import com.aijob.analyzer.profile.domain.SourceReference;
import com.aijob.analyzer.requirement.domain.AtomicRequirement;
import com.aijob.analyzer.requirement.domain.RequirementPriority;
import com.aijob.analyzer.retrieval.DefaultProfileChunker;
import com.aijob.analyzer.retrieval.RetrievalQueryBuilder;
import com.aijob.analyzer.retrieval.RetrievalSemanticSnapshot;
import com.aijob.analyzer.retrieval.domain.RankedHit;
import com.aijob.analyzer.retrieval.domain.RetrievalQuery;
import com.aijob.analyzer.retrieval.fusion.RrfConfig;
import com.aijob.analyzer.retrieval.fusion.RrfFusion;
import com.aijob.analyzer.retrieval.keyword.FilesystemLuceneDirectoryProvider;
import com.aijob.analyzer.retrieval.keyword.LuceneKeywordRetriever;
import com.aijob.analyzer.retrieval.keyword.LuceneProfileIndex;
import com.aijob.analyzer.retrieval.port.EmbeddingClient;
import com.aijob.analyzer.retrieval.port.EmbeddingResponse;
import com.aijob.analyzer.retrieval.provider.OpenAiCompatibleEmbeddingClient;
import com.aijob.analyzer.retrieval.provider.OpenAiCompatibleEmbeddingProperties;
import com.aijob.analyzer.retrieval.vector.ExactCosineRetriever;
import com.aijob.analyzer.retrieval.vector.InMemoryVectorProjectionStore;
import com.aijob.analyzer.retrieval.vector.ProfileVectorIndexer;
import com.aijob.analyzer.retrieval.vector.VectorProjectionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="realProviderEval", matches="true")
class FormalRetrievalEvalV1Test {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ProfileArtifactIdentity PROFILE = new ProfileArtifactIdentity("eval-profile-v1", 1L);

    @Test
    void runFormalRetrievalEvalV1OnceWhenExplicitlyEnabled() throws Exception {

        String baseUrl = requireEnv("AI_EMBEDDING_BASE_URL");
        String apiKey = requireEnv("AI_EMBEDDING_API_KEY");
        String model = requireEnv("AI_EMBEDDING_MODEL");
        assertThat(model).isEqualTo("text-embedding-v4");

        JsonNode profileNode = JSON.readTree(Path.of("docs/eval/datasets/EVAL_PROFILE_V1.json").toFile());
        JsonNode datasetNode = JSON.readTree(Path.of("docs/eval/datasets/RETRIEVAL_DATASET_V1.json").toFile());
        List<ProfileFact> facts = new ArrayList<>();
        for (JsonNode f : profileNode.get("retrievalFacts")) {
            JsonNode s = f.get("source");
            facts.add(new ProfileFact(f.get("factId").asText(), 1L, f.get("factType").asText(),
                    f.get("normalizedText").asText(), new SourceReference(s.get("sourceId").asText(),
                    s.get("sourceTitle").asText(), s.get("sourceSection").asText(), s.get("sourceText").asText())));
        }
        CandidateProfile profile = new CandidateProfile(1L, PROFILE, "eval-resume", "eval-parser", ProfileStatus.READY,
                List.of(), facts, List.of());

        RetrievalSemanticSnapshot snapshot = new RetrievalSemanticSnapshot("query-builder-v1", "lucene-standard",
                "lucene-10.5.1-standard-v1", model, 20, 20, 60, 5);
        RecordingEmbeddingClient embedding = new RecordingEmbeddingClient(
                new OpenAiCompatibleEmbeddingClient(RestClient.builder(), new OpenAiCompatibleEmbeddingProperties(baseUrl, apiKey, model)));
        Path luceneRoot = Path.of("target/eval-lucene-v1");
        Files.createDirectories(luceneRoot);
        FilesystemLuceneDirectoryProvider directory = new FilesystemLuceneDirectoryProvider(luceneRoot.toString());
        VectorProjectionStore vectors = new InMemoryVectorProjectionStore();
        DefaultProfileChunker chunker = new DefaultProfileChunker();
        new LuceneProfileIndex(directory).index(PROFILE, chunker.chunk(profile));
        new ProfileVectorIndexer(embedding, vectors, snapshot).index(PROFILE, chunker.chunk(profile));

        LuceneKeywordRetriever bm25 = new LuceneKeywordRetriever(directory);
        ExactCosineRetriever vector = new ExactCosineRetriever(vectors, embedding, snapshot);
        RrfFusion rrf = new RrfFusion(new RrfConfig(60));
        RetrievalQueryBuilder queryBuilder = new RetrievalQueryBuilder();
        List<Map<String, Object>> rows = new ArrayList<>();
        int positive = 0;
        for (JsonNode c : datasetNode.get("cases")) {
            if (!"RETRIEVAL".equals(c.get("evaluationLane").asText())) continue;
            AtomicRequirement req = new AtomicRequirement(c.get("queryId").asText(), c.get("requirementType").asText(),
                    c.get("requirementText").asText(), RequirementPriority.REQUIRED, c.get("requirementText").asText());
            RetrievalQuery query = queryBuilder.from(req);
            List<String> expected = new ArrayList<>();
            c.get("expectedRelevantFactIds").forEach(n -> expected.add(n.asText()));
            List<RankedHit> bm = bm25.search(PROFILE, query, 20);
            List<RankedHit> ve = vector.search(PROFILE, query, 20);
            var fused = rrf.fuse(bm, ve);
            Map<String, Object> row = new HashMap<>();
            row.put("queryId", c.get("queryId").asText()); row.put("requirementText", c.get("requirementText").asText());
            row.put("queryStyle", c.get("queryStyle").asText()); row.put("expectedRelevantFactIds", expected);
            row.put("bm25", bm); row.put("vector", ve); row.put("rrf", fused);
            row.put("bm25Hit5", hit5(bm, expected)); row.put("vectorHit5", hit5(ve, expected)); row.put("rrfHit5", hit5(fused, expected));
            row.put("bm25FirstRelevantRank", firstRank(bm, expected)); row.put("vectorFirstRelevantRank", firstRank(ve, expected)); row.put("rrfFirstRelevantRank", firstRank(fused, expected));
            rows.add(row); if (!expected.isEmpty()) positive++;
        }
        assertThat(positive).isEqualTo(22);
        Map<String, Object> output = new HashMap<>();
        output.put("datasetVersion", "retrieval-v1"); output.put("profileVersion", "eval-profile-v1");
        output.put("productionBaselineCommit", "8592d97"); output.put("datasetFreezeCommit", "06999c4");
        output.put("evalHead", "WORKTREE"); output.put("timestamp", Instant.now().toString());
        output.put("embeddingRequestedModel", embedding.requestedModel); output.put("embeddingReturnedModel", embedding.returnedModel);
        output.put("embeddingDimension", embedding.dimension); output.put("retrievalConfig", Map.of("keywordCandidateLimit",20,"vectorCandidateLimit",20,"rrfK",60,"finalEvidenceTopK",5));
        output.put("positiveQueryCount", 22); output.put("negativeQueryCount", 5); output.put("structuredExcludedCount", 3); output.put("queries", rows);
        Path result = Path.of("docs/eval/results/retrieval_v1_real_provider_raw.json"); Files.createDirectories(result.getParent()); JSON.writerWithDefaultPrettyPrinter().writeValue(result.toFile(), output);
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("docs/eval/results/retrieval_v1_metrics.json").toFile(), metrics(rows));
    }

    private static Map<String, Object> metrics(List<Map<String, Object>> rows) {
        Map<String, Object> out = new HashMap<>();
        for (String method : List.of("bm25", "vector", "rrf")) {
            long hit = rows.stream().filter(r -> (Boolean) r.get(method + "Hit5") && !((List<?>) r.get("expectedRelevantFactIds")).isEmpty()).count();
            double recall = rows.stream().filter(r -> !((List<?>) r.get("expectedRelevantFactIds")).isEmpty()).mapToDouble(r -> {
                List<?> e = (List<?>) r.get("expectedRelevantFactIds"); List<?> ranked = (List<?>) r.get(method); long found = ranked.stream().limit(5).filter(h -> e.contains(((Map<?, ?>) JSON.convertValue(h, Map.class)).get("factId"))).count(); return (double) found / e.size();
            }).average().orElse(0);
            double mrr = rows.stream().filter(r -> !((List<?>) r.get("expectedRelevantFactIds")).isEmpty()).mapToDouble(r -> { int rank=(Integer)r.get(method+"FirstRelevantRank"); return rank>0&&rank<=5?1.0/rank:0; }).average().orElse(0);
            out.put(method, Map.of("hitAt5Numerator", hit, "hitAt5Denominator", 22, "macroRecallAt5", recall, "mrrAt5", mrr));
        }
        return out;
    }

    private static boolean hit5(List<?> hits, List<String> expected) { return !expected.isEmpty() && hits.stream().limit(5).anyMatch(h -> expected.contains(factId(h))); }
    private static int firstRank(List<?> hits, List<String> expected) { return hits.stream().filter(h -> expected.contains(factId(h))).mapToInt(FormalRetrievalEvalV1Test::rank).findFirst().orElse(0); }
    private static String factId(Object hit) { return hit instanceof RankedHit h ? h.factId() : ((com.aijob.analyzer.retrieval.domain.FusedHit) hit).factId(); }
    private static int rank(Object hit) { return hit instanceof RankedHit h ? h.rank() : ((com.aijob.analyzer.retrieval.domain.FusedHit) hit).rank(); }
    private static String requireEnv(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException(name + " is missing"); return value; }

    private static final class RecordingEmbeddingClient implements EmbeddingClient {
        private final EmbeddingClient delegate; private String requestedModel; private String returnedModel; private int dimension;
        private RecordingEmbeddingClient(EmbeddingClient delegate) { this.delegate = delegate; }
        @Override public EmbeddingResponse embed(String modelIdentity, String text) { requestedModel = modelIdentity; EmbeddingResponse r = delegate.embed(modelIdentity, text); returnedModel = r.providerExposedModelVersion(); dimension = r.vector().length; return r; }
    }
}
