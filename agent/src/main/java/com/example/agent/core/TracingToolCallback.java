package com.example.agent.core;

import java.util.concurrent.TimeUnit;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

public final class TracingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final String origin;

    public TracingToolCallback(ToolCallback delegate, String origin) {
        this.delegate = delegate;
        this.origin = origin;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        long start = System.nanoTime();
        try {
            String result = delegate.call(toolInput, toolContext);
            record(toolContext, toolInput, result, start, false);
            return result;
        }
        catch (RuntimeException ex) {
            record(toolContext, toolInput, ex.getMessage(), start, true);
            throw ex;
        }
    }

    private void record(ToolContext toolContext, String toolInput, String result, long start, boolean error) {
        if (toolContext == null) {
            return;
        }
        Object trace = toolContext.getContext().get(AgentTrace.CONTEXT_KEY);
        if (trace instanceof AgentTrace agentTrace) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            agentTrace.toolCall(origin, getToolDefinition().name(), toolInput, result, durationMs, error);
        }
    }
}
