package com.example.bank.agent;

import com.example.bank.tools.ToolCatalog;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class AccountAgent extends DomainAgent {

    public AccountAgent(ChatClient.Builder chatClientBuilder, ToolCatalog toolCatalog) {
        super(chatClientBuilder, toolCatalog, "account-agent", """
                You are the account specialist in a banking assistant team. You handle
                accounts, balances, cards and the customer profile.

                Rules:
                - Bank data comes ONLY from the bank MCP tools: list_accounts,
                  get_account_balance, list_cards, get_customer_profile. Never invent
                  balances, IBANs or card numbers.
                - Always pass the customer's userId to every MCP tool.
                - Report exact figures including the currency.
                - Return compact Markdown bullet points with account ids, amounts and
                  currencies. Do not write the final polished answer; the writer does that.
                """);
    }
}
