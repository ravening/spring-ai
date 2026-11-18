package com.example.bank.agent;

import java.util.Map;

import com.example.bank.core.AgentTrace;
import com.example.bank.core.PromptSafety;
import com.example.bank.tools.ToolCatalog;

import org.springframework.ai.chat.client.ChatClient;

/**
 * Base class for the domain agents. Each domain agent gets all tools (bank MCP
 * tools plus local tools) and the current conversation/user scope in its tool
 * context, so local tools such as knowledge_search are automatically scoped.
 */
abstract class DomainAgent {

    private final String agentName;
    private final ChatClient chatClient;
    private final ToolCatalog toolCatalog;

    DomainAgent(ChatClient.Builder chatClientBuilder, ToolCatalog toolCatalog,
            String agentName, String systemPrompt) {
        this.agentName = agentName;
        this.toolCatalog = toolCatalog;
        this.chatClient = chatClientBuilder.clone()
                .defaultSystem(systemPrompt + PromptSafety.dataInstruction())
                .build();
    }

    public String run(String goal, String assignment, String conversationId, String userId, AgentTrace trace) {
        trace.activeAgent(agentName);
        String user = """
                Overall goal:
                %s

                Your assignment:
                %s
                """.formatted(goal, assignment);

        return chatClient.prompt()
                .user(user)
                .toolCallbacks(toolCatalog.all())
                .toolContext(Map.of(
                        AgentTrace.CONTEXT_KEY, trace,
                        "conversationId", conversationId,
                        "userId", userId))
                .call()
                .content();
    }
}
