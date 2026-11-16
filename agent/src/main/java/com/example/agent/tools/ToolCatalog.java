package com.example.agent.tools;

import java.util.ArrayList;
import java.util.List;

import com.example.agent.mcpclient.McpStatus;
import com.example.agent.mcpclient.McpToolRegistry;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

@Service
public class ToolCatalog {

    private final LocalToolCatalog localTools;
    private final McpToolRegistry mcpTools;

    public ToolCatalog(LocalToolCatalog localTools, McpToolRegistry mcpTools) {
        this.localTools = localTools;
        this.mcpTools = mcpTools;
    }

    public List<ToolCallback> all() {
        List<ToolCallback> callbacks = new ArrayList<>(localTools.callbacks());
        callbacks.addAll(mcpTools.toolCallbacks());
        return List.copyOf(callbacks);
    }

    public List<ToolCallback> local() {
        return localTools.callbacks();
    }

    public List<String> localNames() {
        return localTools.names();
    }

    public McpStatus mcpStatus() {
        return mcpTools.status();
    }

    public McpStatus refreshMcp() {
        return mcpTools.reconnect();
    }
}
