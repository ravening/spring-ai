package com.example.bank.tools;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Decorates a ToolCallback so values from the client-side ToolContext (for example
 * conversationId and userId) are injected into the tool's JSON arguments when the
 * model did not supply them. This lets a local tool such as knowledge_search stay
 * scoped to the current conversation and user without the model having to repeat
 * those ids on every call.
 */
public final class ContextInjectingToolCallback implements ToolCallback {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ToolCallback delegate;
    private final List<String> contextKeys;

    public ContextInjectingToolCallback(ToolCallback delegate, String... contextKeys) {
        this.delegate = delegate;
        this.contextKeys = List.of(contextKeys);
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
        return delegate.call(inject(toolInput, toolContext), toolContext);
    }

    private String inject(String toolInput, ToolContext toolContext) {
        if (toolContext == null || toolInput == null || toolInput.isBlank()) {
            return toolInput;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(toolInput);
            if (!(node instanceof ObjectNode objectNode)) {
                return toolInput;
            }
            boolean changed = false;
            for (String key : contextKeys) {
                Object value = toolContext.getContext().get(key);
                if (value != null && !objectNode.hasNonNull(key)) {
                    objectNode.put(key, value.toString());
                    changed = true;
                }
            }
            return changed ? OBJECT_MAPPER.writeValueAsString(objectNode) : toolInput;
        }
        catch (Exception ex) {
            return toolInput;
        }
    }
}
