package com.aijob.analyzer.config;

import com.aijob.analyzer.analysis.AnalysisApplicationService;
import com.aijob.analyzer.analysis.concurrency.JvmSingleFlightCoordinator;
import com.aijob.analyzer.analysis.concurrency.SingleFlightCoordinator;
import com.aijob.analyzer.analysis.domain.AnalysisArtifact;
import com.aijob.analyzer.analysis.identity.AnalysisIdentityFactory;
import com.aijob.analyzer.analysis.identity.RequirementIdentityFactory;
import com.aijob.analyzer.analysis.port.AnalysisResultRepository;
import com.aijob.analyzer.cache.RedisArtifactCache;
import com.aijob.analyzer.cache.port.ArtifactCache;
import com.aijob.analyzer.llm.LlmSemanticVersions;
import com.aijob.analyzer.llm.provider.OpenAiCompatibleChatProperties;
import com.aijob.analyzer.profile.port.CandidateProfileRepository;
import com.aijob.analyzer.requirement.port.RequirementSetRepository;
import com.aijob.analyzer.retrieval.DefaultProfileChunker;
import com.aijob.analyzer.retrieval.EvidenceSelector;
import com.aijob.analyzer.retrieval.ProfileChunker;
import com.aijob.analyzer.retrieval.ProfileRetrievalService;
import com.aijob.analyzer.retrieval.ProfileProjectionService;
import com.aijob.analyzer.retrieval.RetrievalQueryBuilder;
import com.aijob.analyzer.retrieval.RetrievalSemanticSnapshot;
import com.aijob.analyzer.retrieval.KeywordRetriever;
import com.aijob.analyzer.retrieval.fusion.RankFusion;
import com.aijob.analyzer.retrieval.fusion.RrfConfig;
import com.aijob.analyzer.retrieval.fusion.RrfFusion;
import com.aijob.analyzer.retrieval.keyword.LuceneDirectoryProvider;
import com.aijob.analyzer.retrieval.keyword.LuceneKeywordRetriever;
import com.aijob.analyzer.retrieval.keyword.LuceneProfileIndex;
import com.aijob.analyzer.retrieval.port.EmbeddingClient;
import com.aijob.analyzer.retrieval.provider.OpenAiCompatibleEmbeddingClient;
import com.aijob.analyzer.retrieval.provider.OpenAiCompatibleEmbeddingProperties;
import com.aijob.analyzer.retrieval.vector.ExactCosineRetriever;
import com.aijob.analyzer.retrieval.vector.InMemoryVectorProjectionStore;
import com.aijob.analyzer.retrieval.vector.ProfileVectorIndexer;
import com.aijob.analyzer.retrieval.vector.VectorProjectionStore;
import com.aijob.analyzer.retrieval.vector.VectorRetriever;
import com.aijob.analyzer.support.retry.DefaultRetryExecutor;
import com.aijob.analyzer.support.retry.JitterSource;
import com.aijob.analyzer.support.retry.RetryExecutor;
import com.aijob.analyzer.support.retry.RetryPolicy;
import com.aijob.analyzer.support.retry.Sleeper;
import com.aijob.analyzer.trace.InProcessTraceRecorder;
import com.aijob.analyzer.trace.SafeTraceRecorder;
import com.aijob.analyzer.trace.TraceRecorder;
import com.aijob.analyzer.workflow.AnalysisOrchestrator;
import com.aijob.analyzer.workflow.AnalysisStage;
import com.aijob.analyzer.workflow.DefaultFallbackResultFactory;
import com.aijob.analyzer.workflow.DefaultRuleTerminalResultFactory;
import com.aijob.analyzer.workflow.DefaultTransitionPolicy;
import com.aijob.analyzer.workflow.StageResult;
import com.aijob.analyzer.workflow.domain.RuleResult;
import com.aijob.analyzer.workflow.domain.RuleSignal;
import com.aijob.analyzer.workflow.domain.StageType;
import com.aijob.analyzer.workflow.port.RuleCheckService;
import com.aijob.analyzer.workflow.port.SafeResultRepair;
import com.aijob.analyzer.workflow.stage.CommitResultStage;
import com.aijob.analyzer.workflow.stage.LlmAnalysisStage;
import com.aijob.analyzer.workflow.stage.ResultValidateStage;
import com.aijob.analyzer.workflow.stage.RetrievalStage;
import com.aijob.analyzer.workflow.stage.RuleCheckStage;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

@Configuration
@Profile("!test")
@EnableConfigurationProperties(OpenAiCompatibleEmbeddingProperties.class)
public class ProductionWiringConfiguration {

    @Bean
    AnalysisIdentityFactory analysisIdentityFactory() { return new AnalysisIdentityFactory(); }

    @Bean
    RequirementIdentityFactory requirementIdentityFactory() { return new RequirementIdentityFactory(); }

    @Bean
    SingleFlightCoordinator<com.aijob.analyzer.analysis.identity.AnalysisIdentity> singleFlightCoordinator() {
        return new JvmSingleFlightCoordinator<>();
    }

    @Bean
    TraceRecorder traceRecorder(JdbcTemplate jdbc, ObjectMapper json) {
        return new SafeTraceRecorder(new InProcessTraceRecorder(new com.aijob.analyzer.trace.JdbcTraceSink(jdbc, json)));
    }

    @Bean
    ArtifactCache<AnalysisArtifact> artifactCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        JavaType type = objectMapper.getTypeFactory().constructType(AnalysisArtifact.class);
        return new RedisArtifactCache<>(redis, objectMapper, type);
    }

    @Bean
    EmbeddingClient embeddingClient(RestClient.Builder builder, OpenAiCompatibleEmbeddingProperties properties, com.aijob.analyzer.llm.ProviderCallLog callLog) {
        return new OpenAiCompatibleEmbeddingClient(builder, properties, callLog);
    }

    @Bean
    RetrievalSemanticSnapshot retrievalSemanticSnapshot(
            @org.springframework.beans.factory.annotation.Value("${app.retrieval.query-builder-version:query-builder-v1}") String queryBuilder,
            @org.springframework.beans.factory.annotation.Value("${app.retrieval.analyzer-id:lucene-standard}") String analyzerId,
            @org.springframework.beans.factory.annotation.Value("${app.retrieval.analyzer-version:lucene-standard-v1}") String analyzerVersion,
            @org.springframework.beans.factory.annotation.Value("${app.embedding.model:}") String embeddingModel,
            @org.springframework.beans.factory.annotation.Value("${app.retrieval.keyword-candidate-limit:20}") int keywordLimit,
            @org.springframework.beans.factory.annotation.Value("${app.retrieval.vector-candidate-limit:20}") int vectorLimit,
            @org.springframework.beans.factory.annotation.Value("${app.retrieval.rrf-k:60}") int rrfK,
            @org.springframework.beans.factory.annotation.Value("${app.retrieval.final-evidence-top-k:5}") int evidenceTopK) {
        return new RetrievalSemanticSnapshot(queryBuilder, analyzerId, analyzerVersion, embeddingModel,
                keywordLimit, vectorLimit, rrfK, evidenceTopK);
    }

    @Bean
    ProfileChunker profileChunker() { return new DefaultProfileChunker(); }

    @Bean
    EvidenceSelector evidenceSelector() { return new EvidenceSelector(); }

    @Bean
    RetrievalQueryBuilder retrievalQueryBuilder() { return new RetrievalQueryBuilder(); }

    @Bean
    LuceneKeywordRetriever keywordRetriever(LuceneDirectoryProvider provider) { return new LuceneKeywordRetriever(provider); }

    @Bean
    LuceneProfileIndex luceneProfileIndex(LuceneDirectoryProvider provider) { return new LuceneProfileIndex(provider); }

    @Bean
    VectorProjectionStore vectorProjectionStore(JdbcTemplate jdbc, ObjectMapper json) {
        return new com.aijob.analyzer.retrieval.vector.JdbcVectorProjectionStore(jdbc, json);
    }

    @Bean
    ProfileVectorIndexer profileVectorIndexer(EmbeddingClient embeddingClient, VectorProjectionStore store,
                                              RetrievalSemanticSnapshot snapshot) {
        return new ProfileVectorIndexer(embeddingClient, store, snapshot);
    }

    @Bean
    ProfileProjectionService profileProjectionService(ProfileChunker chunker, LuceneProfileIndex keywordIndex,
                                                       ProfileVectorIndexer vectorIndexer) {
        return new ProfileProjectionService(chunker, keywordIndex, vectorIndexer);
    }

    @Bean
    VectorRetriever vectorRetriever(VectorProjectionStore store, EmbeddingClient embeddingClient, RetrievalSemanticSnapshot snapshot) {
        return new ExactCosineRetriever(store, embeddingClient, snapshot);
    }

    @Bean
    RankFusion rankFusion(RetrievalSemanticSnapshot snapshot) { return new RrfFusion(new RrfConfig(snapshot.rrfK())); }

    @Bean
    ProfileRetrievalService profileRetrievalService(
            KeywordRetriever keywordRetriever,
            VectorRetriever vectorRetriever,
            RankFusion rankFusion,
            EvidenceSelector evidenceSelector,
            RetrievalQueryBuilder queryBuilder,
            CandidateProfileRepository profileRepository,
            ProfileChunker chunker,
            RetrievalSemanticSnapshot snapshot) {
        return new ProfileRetrievalService(keywordRetriever, vectorRetriever, rankFusion, evidenceSelector,
                queryBuilder, profileRepository, chunker, snapshot);
    }

    @Bean
    RuleCheckService ruleCheckService(com.aijob.analyzer.rules.RuleConfigService configs) {
        return new com.aijob.analyzer.rules.RulesEngine(configs);
    }

    @Bean
    SafeResultRepair safeResultRepair() { return (candidate, error, context) -> Optional.empty(); }

    @Bean
    RetryExecutor retryExecutor() {
        Sleeper sleeper = duration -> { if (!duration.isZero()) Thread.sleep(duration.toMillis()); };
        JitterSource jitter = () -> 0.0;
        return new DefaultRetryExecutor(sleeper, jitter);
    }

    @Bean
    AnalysisOrchestrator analysisOrchestrator(
            RuleCheckService ruleCheckService,
            ProfileRetrievalService retrievalService,
            com.aijob.analyzer.requirement.RequirementResolutionService requirementResolutionService,
            LlmSemanticVersions versions,
            com.aijob.analyzer.llm.FinalAnalysisService finalAnalysisService,
            com.aijob.analyzer.llm.recovery.LlmRecoveryPolicy recoveryPolicy,
            com.aijob.analyzer.workflow.port.ResultValidator resultValidator,
            SafeResultRepair safeResultRepair,
            AnalysisResultRepository repository,
            TraceRecorder traceRecorder,
            RetryExecutor retryExecutor) {
        Map<StageType, AnalysisStage> stages = new EnumMap<>(StageType.class);
        stages.put(StageType.RULE_CHECK, new RuleCheckStage(ruleCheckService));
        stages.put(StageType.PROFILE_RETRIEVAL, new RetrievalStage(retrievalService, requirementResolutionService, versions));
        stages.put(StageType.LLM_ANALYSIS, new LlmAnalysisStage(finalAnalysisService, versions, recoveryPolicy));
        stages.put(StageType.RESULT_VALIDATE, new ResultValidateStage(traceRecorder, resultValidator, safeResultRepair));
        stages.put(StageType.COMMIT_RESULT, new CommitResultStage(repository, traceRecorder));

        Map<StageType, RetryPolicy> policies = new EnumMap<>(StageType.class);
        for (StageType type : StageType.values()) {
            policies.put(type, new RetryPolicy(3, Duration.ZERO, 1.0, Duration.ZERO, 0.0));
        }
        return new AnalysisOrchestrator(stages, new DefaultTransitionPolicy(),
                new DefaultRuleTerminalResultFactory(), new DefaultFallbackResultFactory(),
                traceRecorder, retryExecutor, policies);
    }
}
