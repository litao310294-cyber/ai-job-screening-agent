package com.aijob.analyzer.llm.provider;

import com.aijob.analyzer.config.ProviderHttpConfiguration;
import com.aijob.analyzer.llm.*;
import com.aijob.analyzer.llm.port.ChatModelRequest;
import com.aijob.analyzer.llm.error.ProviderTransportException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class ProviderHttpTimeoutTest {
    @Test void boundedReadTimeoutUsesExistingProviderTransportFailure() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try { Thread.sleep(250); exchange.sendResponseHeaders(200, -1); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException ignored) { /* Client has timed out. */ }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var builder = RestClient.builder(); new ProviderHttpConfiguration().providerTimeouts(1000, 30).customize(builder);
            var properties = new OpenAiCompatibleChatProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/"); properties.setApiKey("TEST");
            var provider = new OpenAiCompatibleChatModelClient(builder, properties);
            var semantics = new LlmSemanticSnapshot("TEST", "TEST", "TEST", new LlmGenerationConfigSnapshot(BigDecimal.ZERO, BigDecimal.ONE, 10, "json_object"));
            assertThrows(ProviderTransportException.class, () -> provider.generate(new ChatModelRequest(semantics, "TEST", "TEST")));
        } finally { server.stop(0); }
    }
}
