package com.aijob.analyzer.chat;

import com.aijob.analyzer.llm.port.*;
import com.aijob.analyzer.llm.validation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import static com.aijob.analyzer.chat.ChatModels.*;

@Service @Profile("!test")
public class ChatService {
    private final ChatRepository repository;
    private final ChatContextBuilder contexts;
    private final ChatModelClient provider;
    private final JsonContractParser parser;
    private final boolean migrations;
    public ChatService(ChatRepository repository, ChatContextBuilder contexts, ChatModelClient provider,
                       JsonContractParser parser, @Value("${spring.flyway.enabled:true}") boolean migrations) {
        this.repository = repository; this.contexts = contexts; this.provider = provider; this.parser = parser; this.migrations = migrations;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() { if (migrations) repository.recover(); }
    public Turn submit(Input input) {
        String scope = contexts.scope(input);
        var claim = repository.claim(input, scope, () -> contexts.freeze(input, scope));
        if (!claim.execute()) return repository.view(claim.stored());
        var frozen = claim.stored().context();
        Reply reply = null; String error = null;
        try {
            var response = provider.generate(new ChatModelRequest(frozen.semantics(), frozen.systemPrompt(), frozen.userPrompt()));
            if ("length".equals(response.finishReason())) throw new SchemaValidationError("Chat response truncated");
            reply = parser.parse(response.rawContent(), Reply.class);
            if (!frozen.allowedEvidenceRefs().containsAll(reply.evidenceRefs())) throw new GroundingError("Unknown chat evidence reference");
        } catch (Exception e) {
            reply = null;
            error = e instanceof com.aijob.analyzer.llm.error.ProviderTransportException ? "CHAT_MODEL_TIMEOUT" :
                    e instanceof SchemaValidationError || e instanceof JsonParseException ? "CHAT_OUTPUT_INVALID" :
                    e instanceof GroundingError ? "CHAT_EVIDENCE_INVALID" : "CHAT_MODEL_FAILED";
        }
        return repository.finish(claim.stored(), reply, error);
    }
}
