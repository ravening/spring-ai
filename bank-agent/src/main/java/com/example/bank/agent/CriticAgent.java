package com.example.bank.agent;

import java.util.List;

import com.example.bank.core.AgentTrace;
import com.example.bank.core.PromptSafety;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class CriticAgent {

    private final ChatClient chatClient;

    public CriticAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.clone()
                .defaultSystem("""
                        You are the reviewer in a banking assistant team.

                        Check the draft against the goal and the evidence gathered by the team.
                        Look for missing facts, unsupported claims, wrong or missing currencies,
                        arithmetic mistakes, contradictions and unclear wording.

                        Return a structured verdict. Set approved to true only when the draft
                        is ready for the customer. Otherwise list concrete issues to fix in
                        the issues field and explain the decision in the summary field.
                        Do not rewrite the draft.

                        """ + PromptSafety.dataInstruction())
                .build();
    }

    public Review review(String goal, String draft, List<StepResult> results, AgentTrace trace) {
        trace.activeAgent("critic");
        String user = """
                Goal:
                %s

                Evidence gathered by the team:
                %s

                Draft to review:
                %s
                """.formatted(goal, AgentSupport.priorResults(results), PromptSafety.data(draft));

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
