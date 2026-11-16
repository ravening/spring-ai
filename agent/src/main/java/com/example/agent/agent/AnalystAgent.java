package com.example.agent.agent;

import java.util.List;
import java.util.Map;

import com.example.agent.core.AgentTrace;
import com.example.agent.tools.LocalToolCatalog;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class AnalystAgent {

    private final ChatClient chatClient;
    private final LocalToolCatalog localToolCatalog;

    public AnalystAgent(ChatClient.Builder chatClientBuilder, LocalToolCatalog localToolCatalog) {
        this.localToolCatalog = localToolCatalog;
        this.chatClient = chatClientBuilder.clone()
                .defaultSystem("""
                        You are the quantitative analyst in a multi-agent team.

                        Use the calculator tool for every arithmetic operation. Work from the
                        research findings provided by earlier steps. Show the calculation and
                        the result explicitly. Never invent numbers.

                        Return concise Markdown with the calculation, the result and a short
                        interpretation. Do not write the final polished answer.
                        """)
                .build();
    }

    public String run(String goal, String instruction, List<StepResult> priorResults, AgentTrace trace) {
        trace.activeAgent("analyst");
        String user = """
                Overall goal:
                %s

                Your assignment:
                %s

                Research findings from earlier steps:
                %s
                """.formatted(goal, instruction, AgentSupport.priorResults(priorResults));

        return chatClient.prompt()
                .user(user)
                .toolCallbacks(localToolCatalog.byName("calculator"))
                .toolContext(Map.of(AgentTrace.CONTEXT_KEY, trace))
                .call()
                .content();
    }
}
