package com.example.agent.chat;

import java.util.List;
import java.util.Map;

import com.example.agent.core.AgentTrace;
import com.example.agent.core.ConversationId;
import com.example.agent.core.ToolCallRecord;
import com.example.agent.tools.ToolCatalog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatClient chatClient;
    private final ToolCatalog toolCatalog;
    private final ObjectMapper objectMapper;

    public ChatService(ChatClient assistantChatClient, ToolCatalog toolCatalog, ObjectMapper objectMapper) {
        this.chatClient = assistantChatClient;
        this.toolCatalog = toolCatalog;
        this.objectMapper = objectMapper;
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

    /**
     * Streams the assistant answer as Server-Sent Events. Text tokens are emitted as
     * ordinary {@code data} events; a final {@code tool_calls} event carries the tool
     * trace as JSON so the UI can render it exactly as it does for {@link #chat(ChatRequest)}.
     */
    public Flux<ServerSentEvent<String>> stream(ChatRequest request) {
        if (request == null || !StringUtils.hasText(request.message())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }

        String conversationId = ConversationId.resolve(request.conversationId());
        AgentTrace trace = new AgentTrace(conversationId);
        trace.activeAgent("assistant");
        trace.event("chat_started", "User message received");

        Flux<ServerSentEvent<String>> tokens = chatClient.prompt()
                .user(request.message())
                .toolCallbacks(toolCatalog.all())
                .toolContext(Map.of(AgentTrace.CONTEXT_KEY, trace))
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content()
                .map(token -> ServerSentEvent.builder(token).build());

        Flux<ServerSentEvent<String>> completion = Flux.defer(() -> {
            trace.event("chat_completed", "Assistant response ready");
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("tool_calls")
                    .data(writeToolCalls(trace.toolCalls()))
                    .build());
        });

        return Flux.concat(tokens, completion)
                .doOnError(ex -> trace.event("chat_failed", "Stream failed: " + ex.getMessage()))
                .onErrorResume(ex -> Flux.just(ServerSentEvent.<String>builder()
                        .event("error")
                        .data(ex.getMessage() == null ? "Stream failed" : ex.getMessage())
                        .build()));
    }

    private String writeToolCalls(List<ToolCallRecord> toolCalls) {
        try {
            return objectMapper.writeValueAsString(toolCalls);
        }
        catch (JsonProcessingException ex) {
            log.warn("Could not serialize tool calls for streaming response", ex);
            return "[]";
        }
    }
}
