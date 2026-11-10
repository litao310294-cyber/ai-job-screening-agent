package com.aijob.analyzer.demo;

import com.aijob.analyzer.AiJobAnalyzerApplication;
import com.aijob.analyzer.llm.port.*;
import com.aijob.analyzer.retrieval.port.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import java.util.*;

/** Test classpath launcher only. No production profile installs these providers. */
public class ExtensionDemoTestServer {
    public static void main(String[] args) {
        new SpringApplicationBuilder(AiJobAnalyzerApplication.class, Providers.class).run(args);
        System.out.println("TEST PROVIDERS ACTIVE: extension integration; no paid model requests");
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class Providers {
        @Bean @Primary ChatModelClient testExtensionProvider() {
            var core = new ChatHttpIntegrationTest.Providers().testChatProvider();
            return request -> {
                if (request.systemPrompt().contains("Resume Parser")) {
                    String quote = "Java";
                    if (!request.userPrompt().contains(quote)) throw new IllegalStateException("TEST resume requires literal Java evidence");
                    return ChatHttpIntegrationTest.Providers.response("{\"structuredFacts\":[],\"retrievalFacts\":[{\"factType\":\"SKILL\",\"normalizedText\":\"Java TEST PROVIDER\",\"source\":{\"sourceId\":\"TEST\",\"sourceTitle\":\"TEST\",\"sourceSection\":\"skills\",\"sourceText\":\"Java\"}}],\"contextOnly\":[]}");
                }
                if(request.userPrompt().contains("LATE_TEST")) {
                    try { Thread.sleep(2500); } catch(InterruptedException e) {Thread.currentThread().interrupt();}
                }
                return core.generate(request);
            };
        }
        @Bean @Primary EmbeddingClient testExtensionEmbedding() {return (model,text)->new EmbeddingResponse(new float[]{1,0,0},model,"TEST",1);}
    }
}
