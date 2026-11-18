package com.example.bank.mcpclient;

import java.util.List;

public record McpStatus(String state, String endpoint, List<McpToolInfo> tools, String error) {
}
