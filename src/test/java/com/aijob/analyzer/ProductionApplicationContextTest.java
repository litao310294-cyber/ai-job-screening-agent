package com.aijob.analyzer;

import com.aijob.analyzer.analysis.AnalysisApplicationService;
import com.aijob.analyzer.analysis.identity.AnalysisIdentityFactory;
import com.aijob.analyzer.cache.port.ArtifactCache;
import com.aijob.analyzer.llm.port.ChatModelClient;
import com.aijob.analyzer.persistence.jdbc.JdbcAnalysisResultRepository;
import com.aijob.analyzer.persistence.jdbc.JdbcCandidateProfileRepository;
import com.aijob.analyzer.persistence.jdbc.JdbcRequirementSetRepository;
import com.aijob.analyzer.retrieval.port.EmbeddingClient;
import com.aijob.analyzer.trace.TraceRecorder;
import com.aijob.analyzer.workflow.AnalysisOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:productionSmoke;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false"
})
@ActiveProfiles("smoke")
class ProductionApplicationContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void productionBeanGraphLoadsWithoutCallingExternalProviders() {
        assertNotNull(context.getBean(AnalysisApplicationService.class));
        assertNotNull(context.getBean(AnalysisIdentityFactory.class));
        assertNotNull(context.getBean(ChatModelClient.class));
        assertNotNull(context.getBean(EmbeddingClient.class));
        assertNotNull(context.getBean(AnalysisOrchestrator.class));
        assertNotNull(context.getBean(TraceRecorder.class));
        assertFalse(context.getBeansOfType(ArtifactCache.class).isEmpty());
        assertNotNull(context.getBean(JdbcCandidateProfileRepository.class));
        assertNotNull(context.getBean(JdbcRequirementSetRepository.class));
        assertNotNull(context.getBean(JdbcAnalysisResultRepository.class));
    }
}
