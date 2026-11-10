package com.aijob.analyzer.llm.port;

public interface ChatModelClient {
    ChatModelResponse generate(ChatModelRequest request);
}
