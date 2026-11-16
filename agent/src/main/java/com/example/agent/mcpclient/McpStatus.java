package com.example.agent.mcpclient;

import java.util.List;

public record McpStatus(String state, String endpoint, List<McpToolInfo> tools, String error) {
}
