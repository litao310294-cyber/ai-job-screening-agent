package com.aijob.analyzer.support;

import com.aijob.analyzer.llm.port.ChatModelClient;
import com.aijob.analyzer.llm.port.ChatModelRequest;
import com.aijob.analyzer.llm.port.ChatModelResponse;

import java.util.LinkedList;
import java.util.Queue;

public class ScriptedChatModelClient implements ChatModelClient {
    private final Queue<Object> script = new LinkedList<>();
    private int invocationCount = 0;
    private ChatModelRequest lastRequest;

    public void addScript(ChatModelResponse response) {
        script.add(response);
    }

    public void addScript(RuntimeException exception) {
        script.add(exception);
    }

    @Override
    public ChatModelResponse generate(ChatModelRequest request) {
        this.lastRequest = request;
        invocationCount++;
        if (script.isEmpty()) {
            throw new IllegalStateException("Script exhausted for ScriptedChatModelClient");
        }
        Object next = script.poll();
        if (next instanceof ChatModelResponse response) {
            return response;
        } else if (next instanceof RuntimeException exception) {
            throw exception;
        } else {
            throw new IllegalStateException("Unexpected type in script: " + next.getClass());
        }
    }

    public int getInvocationCount() {
        return invocationCount;
    }

    public com.aijob.analyzer.llm.port.ChatModelRequest getLastRequest() {
        return lastRequest;
    }
}
