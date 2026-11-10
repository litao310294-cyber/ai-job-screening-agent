package com.aijob.analyzer.demo;

import com.aijob.analyzer.AiJobAnalyzerApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.nio.file.Files;
import java.nio.file.Path;

/** Test-classpath-only launcher for two independent JVM restart checks. */
public class DemoHttpTestServer {
    public static void main(String[] args) throws Exception {
        var app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(
                AiJobAnalyzerApplication.class, DemoHttpIntegrationTest.TestProviders.class).run(args);
        Files.writeString(Path.of(System.getProperty("phase1.portFile")), Integer.toString(app.getWebServer().getPort()));
        System.out.println("TEST PROVIDERS ACTIVE: deterministic chat, embedding and test-only rule");
    }
}
