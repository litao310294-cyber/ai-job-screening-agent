package com.aijob.analyzer.support;

import com.aijob.analyzer.llm.port.ChatModelRequest;
import com.aijob.analyzer.llm.port.ChatModelResponse;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScriptedChatModelClientTest {

    @Test
    void scriptedResponsesReturnedInOrder() {
        ScriptedChatModelClient client = new ScriptedChatModelClient();
        ChatModelResponse res1 = new ChatModelResponse("res1", "id1", "m", "v", 1, 1, "stop");
        ChatModelResponse res2 = new ChatModelResponse("res2", "id2", "m", "v", 1, 1, "stop");
        client.addScript(res1);
        client.addScript(res2);

        ChatModelRequest req = new ChatModelRequest(null, "sys", "user");
        assertEquals("res1", client.generate(req).rawContent());
        assertEquals("res2", client.generate(req).rawContent());
        assertEquals(2, client.getInvocationCount());
    }

    @Test
    void scriptedRuntimeExceptionPropagated() {
        ScriptedChatModelClient client = new ScriptedChatModelClient();
        RuntimeException ex = new RuntimeException("fail");
        client.addScript(ex);

        assertThrows(RuntimeException.class, () -> client.generate(new ChatModelRequest(null, "sys", "user")));
    }

    @Test
    void exhaustedScriptFails() {
        ScriptedChatModelClient client = new ScriptedChatModelClient();
        assertThrows(IllegalStateException.class, () -> client.generate(new ChatModelRequest(null, "sys", "user")));
    }
}
