package com.example.bank.agent;

import java.util.List;

import com.example.bank.core.AgentTrace;
import com.example.bank.core.PromptSafety;
import com.example.bank.users.BankUser;
import com.example.bank.users.UserDirectory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Coordinates the banking agent team for one user message: the router picks a
 * domain agent, the domain agent gathers facts via MCP/local tools, the writer
 * drafts the answer and the critic reviews it with a bounded revision loop.
 */
@Service
public class BankOrchestrator {

    private static final int MAX_REVISIONS_DEFAULT = 2;

    private final RouterAgent router;
    private final AccountAgent accountAgent;
    private final TransactionAgent transactionAgent;
    private final KnowledgeAgent knowledgeAgent;
    private final GeneralAgent generalAgent;
    private final WriterAgent writer;
    private final CriticAgent critic;
    private final ChatMemory chatMemory;
    private final UserDirectory users;
    private final int maxRevisions;

    public BankOrchestrator(
            RouterAgent router,
            AccountAgent accountAgent,
            TransactionAgent transactionAgent,
            KnowledgeAgent knowledgeAgent,
            GeneralAgent generalAgent,
            WriterAgent writer,
            CriticAgent critic,
            ChatMemory chatMemory,
            UserDirectory users,
            @Value("${bank.agents.max-revisions:" + MAX_REVISIONS_DEFAULT + "}") int maxRevisions) {
        this.router = router;
        this.accountAgent = accountAgent;
        this.transactionAgent = transactionAgent;
        this.knowledgeAgent = knowledgeAgent;
        this.generalAgent = generalAgent;
        this.writer = writer;
        this.critic = critic;
        this.chatMemory = chatMemory;
        this.users = users;
        this.maxRevisions = Math.max(0, maxRevisions);
    }

    public String run(String userId, String conversationId, String message, AgentTrace trace) {
        trace.event("run_started", "orchestrator", "Task: " + AgentSupport.truncate(message, 500), null, null);

        String goal = buildGoal(userId, conversationId, message);
        RouteDecision route = router.route(goal, trace);

        long start = System.nanoTime();
        String domainOutput = dispatch(route, goal, conversationId, userId, trace);
        long durationMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        trace.event("agent_result", route.intent(), AgentSupport.summarize(domainOutput, 200),
                AgentSupport.truncate(domainOutput, 4000), durationMs);

        StepResult step = new StepResult(route.intent(), route.note(), domainOutput);
        String draft = writer.synthesize(goal, List.of(step), trace);
        trace.event("draft_ready", "writer", "Writer produced a draft", null, null);

        String finalAnswer = reviewAndRevise(goal, draft, List.of(step), trace);
        trace.event("run_completed", "orchestrator", "Banking run completed", null, null);
        return finalAnswer;
    }

    private String dispatch(RouteDecision route, String goal, String conversationId, String userId, AgentTrace trace) {
        String assignment = StringUtils.hasText(route.note()) ? route.note() : "Handle the customer's request.";
        trace.event("delegation", "orchestrator", "Routed to " + route.intent(), assignment, null);
        return switch (route.intent()) {
            case "ACCOUNTS", "CARDS", "PROFILE" ->
                    accountAgent.run(goal, assignment, conversationId, userId, trace);
            case "TRANSACTIONS", "SPENDING", "INCOME", "SEARCH" ->
                    transactionAgent.run(goal, assignment, conversationId, userId, trace);
            case "KNOWLEDGE" ->
                    knowledgeAgent.run(goal, assignment, conversationId, userId, trace);
            default ->
                    generalAgent.run(goal, assignment, conversationId, userId, trace);
        };
    }

    /** Reviews the draft and asks the writer to revise at most maxRevisions times. */
    private String reviewAndRevise(String goal, String draft, List<StepResult> results, AgentTrace trace) {
        Review review = critic.review(goal, draft, results, trace);
        int revisions = 0;
        while (!review.approved() && revisions < maxRevisions) {
            trace.event("review", "critic", "Issues found", review.feedback(), null);
            revisions++;
            draft = writer.revise(goal, draft, review.feedback(), trace);
            trace.event("revision", "writer", "Writer revised the answer (" + revisions + ")", null, null);
            review = critic.review(goal, draft, results, trace);
        }
        if (review.approved()) {
            trace.event("review", "critic", "Draft approved", review.summary(), null);
        }
        else {
            trace.event("review_unresolved", "critic",
                    "Max revisions reached; returning the best draft", review.feedback(), null);
        }
        return draft;
    }

    /**
     * Builds the goal handed to every agent: the current customer (so agents pass the
     * right userId to banking tools), the recent conversation as untrusted data for
     * follow-up resolution, and the current request.
     */
    private String buildGoal(String userId, String conversationId, String message) {
        BankUser user = users.resolve(userId);
        StringBuilder goal = new StringBuilder();
        goal.append("Current customer: ").append(user.displayName())
                .append(" (userId=\"").append(user.id()).append("\", segment=").append(user.segment())
                .append(", home branch=").append(user.homeBranch()).append(")\n");
        goal.append("Pass userId=\"").append(user.id())
                .append("\" to every banking MCP tool call. Never use another user's data.\n\n");

        List<Message> messages = chatMemory.get(conversationId).stream()
                .filter(m -> m.getMessageType() == MessageType.USER || m.getMessageType() == MessageType.ASSISTANT)
                .filter(m -> StringUtils.hasText(m.getText()))
                .toList();
        // The current message was already persisted before the run started; do not
        // include it twice in the history block.
        if (!messages.isEmpty() && messages.get(messages.size() - 1).getMessageType() == MessageType.USER
                && message.equals(messages.get(messages.size() - 1).getText())) {
            messages = messages.subList(0, messages.size() - 1);
        }
        if (!messages.isEmpty()) {
            List<Message> recent = messages.subList(Math.max(0, messages.size() - 6), messages.size());
            StringBuilder history = new StringBuilder("Recent conversation:\n");
            for (Message m : recent) {
                history.append(m.getMessageType() == MessageType.USER ? "User: " : "Assistant: ")
                        .append(AgentSupport.truncate(m.getText().replaceAll("\\s+", " ").strip(), 600))
                        .append('\n');
            }
            goal.append(PromptSafety.data(history.toString().trim())).append("\n\n");
        }
        goal.append("Current request: ").append(message);
        return goal.toString();
    }
}
