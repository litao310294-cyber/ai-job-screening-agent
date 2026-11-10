package com.aijob.analyzer.cache;

import com.aijob.analyzer.cache.model.CacheAccessStatus;
import com.aijob.analyzer.cache.model.CacheLookupOutcome;
import com.aijob.analyzer.cache.model.CacheReadResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Testcontainers(disabledWithoutDocker = true)
class RedisIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @Test
    void redisCacheReadWriteCycle() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379))
        );
        factory.afterPropertiesSet();

        StringRedisTemplate template = new StringRedisTemplate(factory);
        ObjectMapper mapper = new ObjectMapper();
        RedisArtifactCache<String> cache = new RedisArtifactCache<>(
                template,
                mapper,
                mapper.getTypeFactory().constructType(String.class)
        );

        // 1. Initial MISS
        CacheReadResult<String> miss = cache.get("k1");
        assertEquals(CacheLookupOutcome.MISS, miss.lookupOutcome());
        assertNull(miss.value());

        // 2. PUT
        cache.put("k1", "val1", Duration.ofMinutes(1));

        // 3. HIT
        CacheReadResult<String> hit = cache.get("k1");
        assertEquals(CacheLookupOutcome.HIT, hit.lookupOutcome());
        assertEquals("val1", hit.value());
        assertEquals(CacheAccessStatus.SUCCESS, hit.accessStatus());

        factory.destroy();
    }
}
