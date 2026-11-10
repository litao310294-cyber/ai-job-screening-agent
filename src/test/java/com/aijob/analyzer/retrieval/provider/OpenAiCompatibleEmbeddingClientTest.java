package com.aijob.analyzer.retrieval.provider;

import com.aijob.analyzer.retrieval.port.EmbeddingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OpenAiCompatibleEmbeddingClientTest {

    private OpenAiCompatibleEmbeddingClient client;
    private MockRestServiceServer mockServer;
    private OpenAiCompatibleEmbeddingProperties properties;

    @BeforeEach
    void setUp() {
        properties = new OpenAiCompatibleEmbeddingProperties(
                "http://api.openai.com/v1",
                "sk-test-key",
                "text-embedding-3-small"
        );

        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new OpenAiCompatibleEmbeddingClient(builder, properties);
    }

    @Test
    void should_map_successful_response_correctly() {
        // Given
        String responseBody = """
                {
                  "object": "list",
                  "data": [
                    {
                      "object": "embedding",
                      "index": 0,
                      "embedding": [0.1, 0.2, 0.3]
                    }
                  ],
                  "model": "text-embedding-3-small-v1",
                  "usage": {
                    "prompt_tokens": 5,
                    "total_tokens": 5
                  }
                }
                """;

        mockServer.expect(requestTo("http://api.openai.com/v1/embeddings"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-test-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        // When
        EmbeddingResponse response = client.embed("text-embedding-3-small", "test text");

        // Then
        assertThat(response.vector()).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(response.modelId()).isEqualTo("text-embedding-3-small");
        assertThat(response.providerExposedModelVersion()).isEqualTo("text-embedding-3-small-v1");
        assertThat(response.inputTokens()).isEqualTo(5);
    }

    @Test
    void should_throw_exception_on_429_too_many_requests() {
        mockServer.expect(requestTo("http://api.openai.com/v1/embeddings"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.embed("text-embedding-3-small", "test text"))
                .hasMessageContaining("429");
    }

    @Test
    void should_throw_exception_on_401_unauthorized() {
        mockServer.expect(requestTo("http://api.openai.com/v1/embeddings"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.embed("text-embedding-3-small", "test text"))
                .hasMessageContaining("401");
    }

    @Test
    void should_throw_exception_on_503_service_unavailable() {
        mockServer.expect(requestTo("http://api.openai.com/v1/embeddings"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.embed("text-embedding-3-small", "test text"))
                .hasMessageContaining("503");
    }
}
