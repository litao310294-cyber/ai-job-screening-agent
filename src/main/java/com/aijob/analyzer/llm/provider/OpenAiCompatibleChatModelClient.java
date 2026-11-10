package com.aijob.analyzer.llm.provider;

import com.aijob.analyzer.llm.port.ChatModelClient;
import com.aijob.analyzer.llm.port.ChatModelRequest;
import com.aijob.analyzer.llm.port.ChatModelResponse;
import com.aijob.analyzer.llm.provider.dto.*;
import com.aijob.analyzer.llm.error.ProviderTransportException;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class OpenAiCompatibleChatModelClient implements ChatModelClient {

    private final RestClient.Builder restClientBuilder;
    private final OpenAiCompatibleChatProperties properties;
    private com.aijob.analyzer.llm.ProviderCallLog callLog;

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setCallLog(com.aijob.analyzer.llm.ProviderCallLog callLog) { this.callLog = callLog; }

    public OpenAiCompatibleChatModelClient(RestClient.Builder restClientBuilder, OpenAiCompatibleChatProperties properties) {
        this.properties = properties;
        this.restClientBuilder = restClientBuilder;
    }

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
                || properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("Chat provider is not configured; set AI_CHAT_BASE_URL and AI_CHAT_API_KEY");
        }
        RestClient restClient = restClientBuilder
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .build();
        OpenAiChatRequest openAiRequest = new OpenAiChatRequest(
                request.semanticSnapshot().modelIdentity(),
                List.of(
                        new OpenAiChatMessage("system", request.systemPrompt()),
                        new OpenAiChatMessage("user", request.userPrompt())
                ),
                request.semanticSnapshot().generationConfig().temperature(),
                request.semanticSnapshot().generationConfig().topP(),
                request.semanticSnapshot().generationConfig().maxOutputTokens(),
                "json_object".equals(request.semanticSnapshot().generationConfig().responseFormat()) ? Map.of("type", "json_object") : null
        );

        String callId = callLog == null ? null : callLog.start(request.semanticSnapshot().schemaVersion(), request.semanticSnapshot().modelIdentity(), request.userPrompt().length());
        try {
            OpenAiChatResponse response = restClient.post()
                    .uri("chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(openAiRequest)
                    .retrieve()
                    .body(OpenAiChatResponse.class);

            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                throw new RuntimeException("Empty response from provider");
            }

            OpenAiChatChoice choice = response.choices().get(0);
            if (callLog != null) callLog.finish(callId, "SUCCESS", response.usage() == null ? null : response.usage().promptTokens(),
                    response.usage() == null ? null : response.usage().completionTokens(), choice.finishReason());
            return new ChatModelResponse(
                    choice.message().content(),
                    response.id(),
                    response.model(),
                    response.systemFingerprint(),
                    response.usage() != null ? response.usage().promptTokens() : null,
                    response.usage() != null ? response.usage().completionTokens() : null,
                    choice.finishReason()
            );
        } catch (HttpClientErrorException e) {
            if (callLog != null) callLog.finish(callId, "HTTP_ERROR", null, null, null);
            throw new com.aijob.analyzer.llm.error.ProviderError(
                    e.getStatusCode().value(),
                    e.getStatusText(),
                    e.getResponseBodyAsString()
            );
        } catch (ResourceAccessException e) {
            if (callLog != null) callLog.finish(callId, "TRANSPORT_ERROR", null, null, null);
            throw new ProviderTransportException("Transport error during LLM call", e);
        } catch (Exception e) {
            if (hasTimeoutCause(e)) {
                if (callLog != null) callLog.finish(callId, "TRANSPORT_ERROR", null, null, null);
                throw new ProviderTransportException("Timeout during LLM response", e);
            }
            if (callLog != null) callLog.finish(callId, "FAILED", null, null, null);
            throw new RuntimeException("Unexpected error during LLM call", e);
        }
    }
    private boolean hasTimeoutCause(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof java.net.SocketTimeoutException || current instanceof java.net.http.HttpTimeoutException) return true;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }
}
