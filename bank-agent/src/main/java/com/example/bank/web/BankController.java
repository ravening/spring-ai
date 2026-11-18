package com.example.bank.web;

import java.util.List;

import com.example.bank.mcpclient.McpStatus;
import com.example.bank.tools.ToolCatalog;
import com.example.bank.users.BankUser;
import com.example.bank.users.UserDirectory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BankController {

    private final UserDirectory users;
    private final ToolCatalog toolCatalog;

    public BankController(UserDirectory users, ToolCatalog toolCatalog) {
        this.users = users;
        this.toolCatalog = toolCatalog;
    }

    @GetMapping("/users")
    public List<BankUser> users() {
        return users.list();
    }

    @GetMapping("/tools")
    public ToolsResponse tools() {
        return new ToolsResponse(toolCatalog.localNames(), toolCatalog.mcpStatus());
    }

    @GetMapping("/mcp/status")
    public McpStatus mcpStatus() {
        return toolCatalog.mcpStatus();
    }

    @PostMapping("/mcp/refresh")
    public McpStatus refreshMcp() {
        return toolCatalog.refreshMcp();
    }

    public record ToolsResponse(List<String> localTools, McpStatus mcp) {
    }
}
