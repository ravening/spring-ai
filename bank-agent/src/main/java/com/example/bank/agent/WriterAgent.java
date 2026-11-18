package com.example.bank.agent;

import java.util.List;

import com.example.bank.core.AgentTrace;
import com.example.bank.core.PromptSafety;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class WriterAgent {

    private static final String WRITER_SYSTEM = """
            You are the writer in a banking assistant team.

            Turn the provided findings into a clear, accurate answer for the bank customer.
            Use Markdown. Preserve concrete numbers, amounts, currencies, dates and
            tool-derived facts exactly. Never invent data. Write only what the customer
            should read. Never mention goals, instructions, plans, agents, reviewers,
            evidence, or that a constraint was satisfied. No meta-commentary.
            Honor explicit format limits in the request, such as "one sentence".

            """ + PromptSafety.dataInstruction();

    private final ChatClient chatClient;

    public WriterAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.clone().build();
    }

    public String synthesize(String goal, List<StepResult> results, AgentTrace trace) {
        trace.activeAgent("writer");
        String user = """
                Overall goal:
                %s

                Findings from the team:
                %s

                Write the complete final answer for the customer.
                """.formatted(goal, AgentSupport.priorResults(results));

        return chatClient.prompt()
                .system(WRITER_SYSTEM + "\nThis is the final answer. Be complete but concise.")
                .user(user)
                .call()
                .content();
    }

    public String revise(String goal, String draft, String review, AgentTrace trace) {
        trace.activeAgent("writer");
        String user = """
                Overall goal:
                %s

                Current draft:
                %s

                Reviewer feedback:
                %s

                Rewrite the answer so that every valid issue is addressed. Keep the parts that
                already work. Do not mention the reviewer or the feedback. Return the complete
                revised answer in Markdown.
                """.formatted(goal, PromptSafety.data(draft), PromptSafety.data(review));

        return chatClient.prompt()
                .system(WRITER_SYSTEM + "\nThis is the final answer. Return only the revised answer.")
                .user(user)
                .call()
                .content();
    }
}
