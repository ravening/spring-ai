package com.example.agent.agent;

import java.util.List;

import com.example.agent.core.AgentTrace;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class CriticAgent {

    private final ChatClient chatClient;

    public CriticAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.clone()
                .defaultSystem("""
                        You are the reviewer in a multi-agent team.

                        Check the draft against the goal and the evidence gathered by the team.
                        Look for missing facts, unsupported claims, arithmetic mistakes,
                        contradictions and unclear wording.

                        If the draft is ready, reply with exactly: APPROVED
                        Otherwise return a numbered list of concrete issues to fix.
                        Do not rewrite the draft.
                        """)
                .build();
    }

    public String review(String goal, String draft, List<StepResult> results, AgentTrace trace) {
        trace.activeAgent("critic");
        String user = """
                Goal:
                %s

                Evidence gathered by the team:
                %s

                Draft:
                %s
                """.formatted(goal, AgentSupport.priorResults(results), draft);

        return chatClient.prompt()
                .user(user)
                .call()
                .content();
    }
}
