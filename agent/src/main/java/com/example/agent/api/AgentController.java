package com.example.agent.api;

import com.example.agent.agent.AgentOrchestrator;
import com.example.agent.agent.AgentRequest;
import com.example.agent.agent.AgentRunResponse;
import com.example.agent.chat.ChatRequest;
import com.example.agent.chat.ChatResponse;
import com.example.agent.chat.ChatService;
import com.example.agent.mcpclient.McpStatus;
import com.example.agent.tools.ToolCatalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AgentController {

    private final ChatService chatService;
    private final AgentOrchestrator orchestrator;
    private final ToolCatalog toolCatalog;

    public AgentController(ChatService chatService, AgentOrchestrator orchestrator, ToolCatalog toolCatalog) {
        this.chatService = chatService;
        this.orchestrator = orchestrator;
        this.toolCatalog = toolCatalog;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    @PostMapping("/agent/run")
    public AgentRunResponse runAgent(@RequestBody AgentRequest request) {
        return orchestrator.run(request);
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
}
