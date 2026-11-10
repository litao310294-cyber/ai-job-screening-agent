package com.aijob.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Opt-in check against the local demo infrastructure using application wiring. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:isolated;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false"})
@EnabledIfSystemProperty(named = "demoRedisPing", matches = "true")
class DemoRedisConnectionTest {
    @Autowired RedisProperties properties;
    @Autowired RedisConnectionFactory connectionFactory;

    @Test
    void missingPasswordConnectsToDemoRedis() {
        assertEquals("127.0.0.1", properties.getHost());
        assertEquals(6380, properties.getPort());
        assertEquals("", properties.getPassword());
        try (var connection = connectionFactory.getConnection()) {
            assertEquals("PONG", connection.ping());
        }
    }
}
