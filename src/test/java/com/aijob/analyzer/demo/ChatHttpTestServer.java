package com.aijob.analyzer.demo;

import com.aijob.analyzer.AiJobAnalyzerApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.nio.file.*;

public class ChatHttpTestServer {
    public static void main(String[] args) throws Exception {
        var app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(AiJobAnalyzerApplication.class, ChatHttpIntegrationTest.Providers.class).run(args);
        Files.writeString(Path.of(System.getProperty("phase4.portFile")), Integer.toString(app.getWebServer().getPort()));
        System.out.println("TEST PROVIDERS ACTIVE: phase4 deterministic chat and embedding");
    }
}
