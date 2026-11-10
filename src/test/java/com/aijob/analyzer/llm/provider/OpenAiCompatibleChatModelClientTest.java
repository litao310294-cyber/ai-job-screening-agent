package com.aijob.analyzer.llm.provider;

import com.aijob.analyzer.llm.LlmGenerationConfigSnapshot;
import com.aijob.analyzer.llm.LlmSemanticSnapshot;
import com.aijob.analyzer.llm.error.ProviderTransportException;
import com.aijob.analyzer.llm.port.ChatModelRequest;
import com.aijob.analyzer.llm.port.ChatModelResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@RestClientTest(OpenAiCompatibleChatModelClient.class)
@Import(OpenAiCompatibleChatProperties.class)
@org.springframework.test.context.TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:restClientSlice;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.flyway.enabled=false",
    "app.llm.chat.base-url=http://api.openai.com/v1/",
    "app.llm.chat.api-key=test-key"
})
class OpenAiCompatibleChatModelClientTest {

    @Autowired
    private OpenAiCompatibleChatModelClient client;

    @Autowired
    private MockRestServiceServer server;

    @Autowired
    private OpenAiCompatibleChatProperties properties;

    @Test
    void should_map_successful_response() throws Exception {
        String json = """
                {
                  "id": "chatcmpl-123",
                  "object": "chat.completion",
                  "created": 1677652288,
                  "model": "gpt-4o",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "content": "Hello world"
                    },
                    "finish_reason": "stop"
                  }],
                  "usage": {
                    "prompt_tokens": 9,
                    "completion_tokens": 12,
                    "total_tokens": 21
                  }
                }
                """;

        server.expect(requestTo("http://api.openai.com/v1/chat/completions"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.temperature").value(0))
                .andExpect(jsonPath("$.top_p").value(1))
                .andExpect(jsonPath("$.max_tokens").value(100))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.topP").doesNotExist())
                .andExpect(jsonPath("$.maxTokens").doesNotExist())
                .andExpect(jsonPath("$.responseFormat").doesNotExist())
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        LlmSemanticSnapshot snapshot = new LlmSemanticSnapshot(
                "v1", "gpt-4o", "v1",
                new LlmGenerationConfigSnapshot(BigDecimal.ZERO, BigDecimal.ONE, 100, "json_object")
        );
        ChatModelRequest request = new ChatModelRequest(snapshot, "sys", "user");

        ChatModelResponse response = client.generate(request);

        assertThat(response.rawContent()).isEqualTo("Hello world");
        assertThat(response.modelId()).isEqualTo("gpt-4o");
        assertThat(response.inputTokens()).isEqualTo(9);
    }

    @Test
    void should_handle_429_error() {
        server.expect(requestTo("http://api.openai.com/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        LlmSemanticSnapshot snapshot = new LlmSemanticSnapshot(
                "v1", "gpt-4o", "v1",
                new LlmGenerationConfigSnapshot(BigDecimal.ZERO, BigDecimal.ONE, 100, "json_object")
        );
        ChatModelRequest request = new ChatModelRequest(snapshot, "sys", "user");

        assertThatThrownBy(() -> client.generate(request))
                .isInstanceOfSatisfying(com.aijob.analyzer.llm.error.ProviderError.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(429));
    }

    @Test
    void should_handle_timeout() {
        server.expect(requestTo("http://api.openai.com/v1/chat/completions"))
                .andRespond(withException(new java.io.IOException("timeout")));

        LlmSemanticSnapshot snapshot = new LlmSemanticSnapshot(
                "v1", "gpt-4o", "v1",
                new LlmGenerationConfigSnapshot(BigDecimal.ZERO, BigDecimal.ONE, 100, "json_object")
        );
        ChatModelRequest request = new ChatModelRequest(snapshot, "sys", "user");

        assertThatThrownBy(() -> client.generate(request))
                .isInstanceOf(ProviderTransportException.class);
    }
}
