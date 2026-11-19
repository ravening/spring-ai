package com.example.bank.agent;

import java.util.List;
import java.util.function.Consumer;

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
            Format for readability: short paragraphs, bullet lists when there are several
            items, *italics* for emphasis, and a Markdown table (| Column | ... |) when
            presenting multiple figures, accounts or transactions side by side. Keep
            tables to at most 5 columns.

            """ + PromptSafety.dataInstruction();

    private final ChatClient chatClient;

    public WriterAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.clone().build();
    }

    public String synthesize(String goal, List<StepResult> results, AgentTrace trace,
            Consumer<RunRecord.AnswerEvent> answerSink) {
        trace.activeAgent("writer");
        String user = """
                Overall goal:
                %s

                Findings from the team:
                %s

                Write the complete final answer for the customer.
                """.formatted(goal, AgentSupport.priorResults(results));

        return call(WRITER_SYSTEM + "\nThis is the final answer. Be complete but concise.", user, answerSink);
    }

    public String revise(String goal, String draft, String review, AgentTrace trace,
            Consumer<RunRecord.AnswerEvent> answerSink) {
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

        return call(WRITER_SYSTEM + "\nThis is the final answer. Return only the revised answer.", user, answerSink);
    }

    /**
     * Calls the model; when an answer sink is given, the response is streamed token by
     * token (a {@code newDraft} event marks the start of each (re)written draft) and the
     * full text is still returned for persistence.
     */
    private String call(String system, String user, Consumer<RunRecord.AnswerEvent> answerSink) {
        var prompt = chatClient.prompt().system(system).user(user);
        if (answerSink == null) {
            return prompt.call().content();
        }
        answerSink.accept(new RunRecord.AnswerEvent(true, ""));
        return prompt.stream().content()
                .doOnNext(token -> answerSink.accept(new RunRecord.AnswerEvent(false, token)))
                .collect(StringBuilder::new, StringBuilder::append)
                .map(StringBuilder::toString)
                .block();
    }
}
