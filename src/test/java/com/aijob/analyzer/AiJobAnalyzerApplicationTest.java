package com.aijob.analyzer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:isolated;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false"})
@ActiveProfiles("test")
@EnableAutoConfiguration(exclude = {
    DataSourceAutoConfiguration.class,
    RedisAutoConfiguration.class,
    FlywayAutoConfiguration.class
})
class AiJobAnalyzerApplicationTest {

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.aijob.analyzer.profile.port.CandidateProfileRepository candidateProfileRepository;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.aijob.analyzer.requirement.port.RequirementSetRepository requirementSetRepository;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.aijob.analyzer.llm.port.ChatModelClient chatModelClient;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.aijob.analyzer.support.retry.RetryExecutor retryExecutor;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.aijob.analyzer.retrieval.ProfileProjectionService profileProjectionService;

    @Test
    void contextLoads() {
    }
}
