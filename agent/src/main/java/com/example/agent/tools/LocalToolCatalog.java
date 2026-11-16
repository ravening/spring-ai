package com.example.agent.tools;

import java.util.Arrays;
import java.util.List;

import com.example.agent.core.TracingToolCallback;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

@Component
public class LocalToolCatalog {

    private final List<ToolCallback> callbacks;

    public LocalToolCatalog(LocalTools localTools) {
        this.callbacks = Arrays.stream(MethodToolCallbackProvider.builder()
                        .toolObjects(localTools)
                        .build()
                        .getToolCallbacks())
                .map(callback -> (ToolCallback) new TracingToolCallback(callback, "local"))
                .toList();
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
