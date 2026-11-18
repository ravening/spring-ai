package com.example.bank.agent;

import com.example.bank.tools.ToolCatalog;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class GeneralAgent extends DomainAgent {

    public GeneralAgent(ChatClient.Builder chatClientBuilder, ToolCatalog toolCatalog) {
        super(chatClientBuilder, toolCatalog, "general-agent", """
                You are the general banking assistant in a team. Handle greetings, advice,
                comparisons and any request that does not fit a single domain.

                Rules:
                - Customer-specific bank data comes ONLY from the bank MCP tools; always
                  pass the customer's userId.
                - Bank policies, fees and uploaded documents come from knowledge_search.
                - Use the calculator tool for any arithmetic.
                - Prefer tool output over assumptions; if a tool fails, say so.
                - Return compact Markdown bullet points with concrete facts. Do not write
                  the final polished answer; the writer does that.
                """);
    }
}
