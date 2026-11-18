package com.example.bank.tools;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextInjectingToolCallbackTest {

    private static ToolCallback fake(AtomicReference<String> seen) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return null;
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return null;
            }

            @Override
            public String call(String toolInput) {
                return call(toolInput, null);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                seen.set(toolInput);
                return "ok";
            }
        };
    }

    @Test
    void injectsConversationAndUserWhenAbsent() {
        AtomicReference<String> seen = new AtomicReference<>();
        ContextInjectingToolCallback wrapper =
                new ContextInjectingToolCallback(fake(seen), "conversationId", "userId");
        ToolContext context = new ToolContext(Map.of("conversationId", "conv-1", "userId", "alice"));

        String result = wrapper.call("{\"query\":\"card fees\"}", context);

        assertEquals("ok", result);
        assertTrue(seen.get().contains("\"conversationId\":\"conv-1\""), seen.get());
        assertTrue(seen.get().contains("\"userId\":\"alice\""), seen.get());
        assertTrue(seen.get().contains("\"query\":\"card fees\""), seen.get());
    }

    @Test
    void doesNotOverwriteModelSuppliedValues() {
        AtomicReference<String> seen = new AtomicReference<>();
        ContextInjectingToolCallback wrapper =
                new ContextInjectingToolCallback(fake(seen), "conversationId", "userId");
        ToolContext context = new ToolContext(Map.of("conversationId", "conv-1", "userId", "alice"));

        wrapper.call("{\"query\":\"x\",\"conversationId\":\"other\"}", context);

        assertTrue(seen.get().contains("\"conversationId\":\"other\""), seen.get());
        assertTrue(seen.get().contains("\"userId\":\"alice\""), seen.get());
    }

    @Test
    void passesInputThroughWhenNoContext() {
        AtomicReference<String> seen = new AtomicReference<>();
        ContextInjectingToolCallback wrapper =
                new ContextInjectingToolCallback(fake(seen), "conversationId", "userId");

        wrapper.call("{\"query\":\"x\"}", null);

        assertEquals("{\"query\":\"x\"}", seen.get());
    }
}
