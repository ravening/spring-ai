package com.example.agent.api;

import java.util.List;

import com.example.agent.mcpclient.McpStatus;

public record ToolsResponse(List<String> localTools, McpStatus mcp) {
}
