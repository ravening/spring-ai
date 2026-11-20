package com.example.bank.tools;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.example.bank.core.TracingToolCallback;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

@Component
public class LocalToolCatalog {

    private static final String KNOWLEDGE_TOOL = "knowledge_search";

    /** Tools that need the current conversation/user scope injected from the tool context. */
    private static final Set<String> CONTEXT_SCOPED = Set.of(
            KNOWLEDGE_TOOL, "schedule_job", "list_jobs", "cancel_job", "job_details", "transfer_money");

    private final List<ToolCallback> callbacks;

    public LocalToolCatalog(LocalTools localTools, ScheduledJobTools scheduledJobTools, TransferTools transferTools) {
        this.callbacks = Arrays.stream(MethodToolCallbackProvider.builder()
                        .toolObjects(localTools, scheduledJobTools, transferTools)
                        .build()
                        .getToolCallbacks())
                .map(LocalToolCatalog::decorate)
                .toList();
    }

    private static ToolCallback decorate(ToolCallback callback) {
        ToolCallback decorated = callback;
        if (CONTEXT_SCOPED.contains(callback.getToolDefinition().name())) {
            // These tools stay scoped to the current conversation/user even when the
            // model omits those arguments: inject them from the tool context first.
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
