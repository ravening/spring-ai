package com.example.agent.chat;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.example.agent.core.AgentTrace;
import com.example.agent.tools.ToolCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceStreamTests {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.StreamResponseSpec streamSpec;

    @Mock
    private ToolCatalog toolCatalog;

    @Test
    void streamsTokensThenTerminalToolCallsEvent() {
        when(toolCatalog.all()).thenReturn(List.of());
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(anyList())).thenReturn(requestSpec);
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("Hello", " world"));

        ChatService service = new ChatService(chatClient, toolCatalog);
        Flux<ServerSentEvent<String>> stream = service.stream(new ChatRequest("hi", "chat-1"));

        // Simulate a tool call being traced while the model is streaming.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(requestSpec).toolContext(contextCaptor.capture());
        AgentTrace trace = (AgentTrace) contextCaptor.getValue().get(AgentTrace.CONTEXT_KEY);
        trace.toolCall("local", "calculator", "{\"a\":1}", "1", 5, false);
        assertThat(trace.toolCalls()).hasSize(1);

        List<ServerSentEvent<String>> events = stream.collectList().block();

        assertThat(events).hasSize(4);
        assertThat(events.get(0).event()).isEqualTo("conversation");
        assertThat(events.get(0).data()).isEqualTo("chat-1");
        assertThat(events.get(1).data()).isEqualTo("Hello");
        assertThat(events.get(2).data()).isEqualTo(" world");
        assertThat(events.get(3).event()).isEqualTo("tool_calls");
        assertThat(events.get(3).data()).contains("calculator");
    }

    @Test
    void rejectsBlankMessage() {
        ChatService service = new ChatService(chatClient, toolCatalog);
        assertThatThrownBy(() -> service.stream(new ChatRequest(" ", "chat-1")))
                .isInstanceOf(ResponseStatusException.class);
    }
}
