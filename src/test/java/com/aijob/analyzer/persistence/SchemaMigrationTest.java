package com.aijob.analyzer.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class SchemaMigrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("aijob")
            .withUsername("test")
            .withPassword("test");

    @Test
    void appliesTheVersionedArtifactSchema() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            for (String table : java.util.List.of("candidate_profile_artifact", "candidate_profile_current", "profile_fact", "profile_chunk_projection", "requirement_set", "atomic_requirement", "analysis_canonical_result")) {
                try (var result = statement.executeQuery("SHOW TABLES LIKE '" + table + "'")) {
                    assertTrue(result.next(), table);
                }
            }
        }
    }
}
