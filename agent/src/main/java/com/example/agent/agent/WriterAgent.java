package com.example.agent.agent;

import java.util.List;

import com.example.agent.core.AgentTrace;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class WriterAgent {

    private static final String WRITER_SYSTEM = """
            You are the writer in a multi-agent team.

            Turn the provided research and analysis into clear, accurate prose. Use Markdown.
            Preserve concrete numbers, dates and tool-derived facts. Do not invent data.
            Write only what the user should read. Never mention goals, instructions, plans,
            agents, reviewers, evidence, or that a constraint was satisfied. No meta-commentary.
            Honor explicit format limits in the task, such as "one sentence" or "one paragraph".
            """;

    private final ChatClient chatClient;

    public WriterAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.clone().build();
    }

    public String run(String goal, String instruction, List<StepResult> priorResults, AgentTrace trace) {
        trace.activeAgent("writer");
        String user = """
                Overall goal:
                %s

                Write this section:
                %s

                Material from earlier steps:
                %s
                """.formatted(goal, instruction, AgentSupport.priorResults(priorResults));

        return chatClient.prompt()
                .system(WRITER_SYSTEM + "\nDraft only the requested section. Do not add a final conclusion unless asked.")
                .user(user)
                .call()
                .content();
    }

    public String synthesize(String goal, List<StepResult> results, AgentTrace trace) {
        trace.activeAgent("writer");
        String user = """
                Overall goal:
                %s

                Evidence and analysis from the team:
                %s

                Write the complete final answer for the user.
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
                """.formatted(goal, draft, review);

        return chatClient.prompt()
                .system(WRITER_SYSTEM + "\nThis is the final answer. Return only the revised answer.")
                .user(user)
                .call()
                .content();
    }
}
