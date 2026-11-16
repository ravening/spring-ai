package com.example.agent.chat;

import java.util.Map;

import com.example.agent.core.AgentTrace;
import com.example.agent.core.ConversationId;
import com.example.agent.tools.ToolCatalog;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatService {

    private final ChatClient chatClient;
    private final ToolCatalog toolCatalog;

    public ChatService(ChatClient assistantChatClient, ToolCatalog toolCatalog) {
        this.chatClient = assistantChatClient;
        this.toolCatalog = toolCatalog;
    }

    public ChatResponse chat(ChatRequest request) {
        if (request == null || !StringUtils.hasText(request.message())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }

        String conversationId = ConversationId.resolve(request.conversationId());
        AgentTrace trace = new AgentTrace(conversationId);
        trace.activeAgent("assistant");
        trace.event("chat_started", "User message received");

        String answer = chatClient.prompt()
                .user(request.message())
                .toolCallbacks(toolCatalog.all())
                .toolContext(Map.of(AgentTrace.CONTEXT_KEY, trace))
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        trace.event("chat_completed", "Assistant response ready");
        return new ChatResponse(conversationId, answer, trace.toolCalls());
    }
}
