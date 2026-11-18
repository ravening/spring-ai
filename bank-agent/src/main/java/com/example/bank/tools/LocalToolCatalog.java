package com.example.bank.tools;

import java.util.Arrays;
import java.util.List;

import com.example.bank.core.TracingToolCallback;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

@Component
public class LocalToolCatalog {

    private static final String KNOWLEDGE_TOOL = "knowledge_search";

    private final List<ToolCallback> callbacks;

    public LocalToolCatalog(LocalTools localTools) {
        this.callbacks = Arrays.stream(MethodToolCallbackProvider.builder()
                        .toolObjects(localTools)
                        .build()
                        .getToolCallbacks())
                .map(LocalToolCatalog::decorate)
                .toList();
    }

    private static ToolCallback decorate(ToolCallback callback) {
        ToolCallback decorated = callback;
        if (KNOWLEDGE_TOOL.equals(callback.getToolDefinition().name())) {
            // knowledge_search needs the current conversation/user scope even when the
            // model omits those arguments, so inject them from the tool context first.
            decorated = new ContextInjectingToolCallback(decorated, "conversationId", "userId");
        }
        return new TracingToolCallback(decorated, "local");
    }

    public List<ToolCallback> callbacks() {
        return callbacks;
    }

    public List<ToolCallback> byName(String... names) {
        List<String> requested = List.of(names);
        return callbacks.stream()
                .filter(callback -> requested.contains(callback.getToolDefinition().name()))
                .toList();
    }

    public List<String> names() {
        return callbacks.stream().map(callback -> callback.getToolDefinition().name()).sorted().toList();
    }
}
