package com.aijob.analyzer.support;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Fails before bean creation if a test inherits the forbidden canary database. */
public class IsolationEvidenceInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override public void initialize(ConfigurableApplicationContext context) {
        var env = context.getEnvironment();
        String url = env.getProperty("spring.datasource.url", "");
        if (url.contains("isolation-canary.invalid")) throw new IllegalStateException("Inherited database reached Spring context");
        if (!url.startsWith("jdbc:h2:") && !url.startsWith("jdbc:mysql://localhost:") && !url.startsWith("jdbc:mysql://127.0.0.1:")) {
            throw new IllegalStateException("Unexpected test database target");
        }
        System.out.println("ISOLATION_DATABASE profiles=" + String.join(",", env.getActiveProfiles())
                + " url=" + url + " flyway=" + env.getProperty("spring.flyway.enabled", "true"));
    }
}
