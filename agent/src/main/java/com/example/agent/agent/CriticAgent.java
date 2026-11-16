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

                        Return a structured verdict. Set approved to true only when the draft
                        is ready. Otherwise list concrete issues to fix in the issues field
                        and explain the decision in the summary field. Do not rewrite the draft.
                        """)
                .build();
    }

    public Review review(String goal, String draft, List<StepResult> results, AgentTrace trace) {
        trace.activeAgent("critic");
        String user = """
                Goal:
                %s

                Evidence gathered by the team:
                %s

                Draft:
                %s
                """.formatted(goal, AgentSupport.priorResults(results), draft);

        try {
            Review review = chatClient.prompt()
                    .user(user)
                    .call()
                    .entity(Review.class);
            if (review != null) {
                return review;
            }
        }
        catch (Exception ex) {
            trace.event("review_parse_failed", "critic", "Could not parse the critic's verdict",
                    ex.getMessage(), null);
        }
        return new Review(false, "The critic's verdict could not be parsed.", List.of());
    }
}
