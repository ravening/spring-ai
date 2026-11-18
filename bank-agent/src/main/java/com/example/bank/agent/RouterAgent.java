package com.example.bank.agent;

import com.example.bank.core.AgentTrace;
import com.example.bank.core.PromptSafety;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class RouterAgent {

    private final ChatClient chatClient;

    public RouterAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.clone()
                .defaultSystem("""
                        You are the router in a banking assistant team. Classify the customer's
                        request into exactly one intent:

                        - ACCOUNTS: accounts and balances (what accounts do I have, what is my balance)
                        - TRANSACTIONS: listing transactions (my last 10 transactions, history)
                        - SPENDING: spending summaries and breakdowns (what did I spend this month)
                        - INCOME: income summaries (what is my income this month)
                        - SEARCH: finding specific transactions by keyword (any Amazon payments?)
                        - CARDS: cards, card limits, card status
                        - PROFILE: customer profile and overview
                        - KNOWLEDGE: bank policies, products, fees, security, limits, or anything
                          found in uploaded documents (use the conversation history to spot
                          follow-ups about documents or policies)
                        - GENERAL: greetings, small talk, advice, or anything else

                        In the note field, extract the concrete parameters for the domain agent:
                        month (yyyy-MM), limit (how many transactions), category, account id,
                        keyword, or follow-up references resolved from the conversation
                        history. Keep the note short. Return only the structured decision.

                        """ + PromptSafety.dataInstruction())
                .build();
    }

    public RouteDecision route(String goal, AgentTrace trace) {
        trace.activeAgent("router");
        try {
            RouteDecision decision = chatClient.prompt()
                    .user(goal)
                    .call()
                    .entity(RouteDecision.class);
            if (decision != null) {
                RouteDecision normalized = new RouteDecision(
                        AgentSupport.normalizeIntent(decision.intent()), decision.note());
                trace.event("route", "router", "Intent: " + normalized.intent(), normalized.note(), null);
                return normalized;
            }
        }
        catch (Exception ex) {
            trace.event("route_fallback", "router", "Falling back to GENERAL", ex.getMessage(), null);
        }
        return new RouteDecision("GENERAL", "Fallback routing.");
    }
}
