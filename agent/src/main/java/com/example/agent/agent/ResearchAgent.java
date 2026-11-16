package com.example.agent.agent;

import java.util.List;
import java.util.Map;

import com.example.agent.core.AgentTrace;
import com.example.agent.tools.ToolCatalog;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class ResearchAgent {

    private final ChatClient chatClient;
    private final ToolCatalog toolCatalog;

    public ResearchAgent(ChatClient.Builder chatClientBuilder, ToolCatalog toolCatalog) {
        this.toolCatalog = toolCatalog;
        this.chatClient = chatClientBuilder.clone()
                .defaultSystem("""
                        You are the research specialist in a multi-agent team.

                        Gather the facts requested using the available local and MCP tools.
                        Prefer tool output over memory or assumptions. When you use a tool,
                        mention the tool name and include the concrete result.

                        Return compact Markdown bullet points with numbers, dates and names.
                        Do not write the final polished answer; another agent will do that.
                        """)
                .build();
    }

    public String run(String goal, String instruction, List<StepResult> priorResults, AgentTrace trace) {
        trace.activeAgent("researcher");
        String user = """
                Overall goal:
                %s

                Your assignment:
                %s

                Findings from earlier steps:
                %s
                """.formatted(goal, instruction, AgentSupport.priorResults(priorResults));

        return chatClient.prompt()
                .user(user)
                .toolCallbacks(toolCatalog.all())
                .toolContext(Map.of(AgentTrace.CONTEXT_KEY, trace))
                .call()
                .content();
    }
}
