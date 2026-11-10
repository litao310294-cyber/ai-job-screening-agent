package com.aijob.analyzer.demo;

import com.aijob.analyzer.AiJobAnalyzerApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.nio.file.*;

public class ProfileImportTestServer {
    public static void main(String[] args) throws Exception {
        var app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(AiJobAnalyzerApplication.class,ProfileImportHttpIntegrationTest.TestProviders.class).run(args);
        Files.writeString(Path.of(System.getProperty("phase2a.portFile")),Integer.toString(app.getWebServer().getPort()));
    }
}
