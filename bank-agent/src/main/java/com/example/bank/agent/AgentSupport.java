package com.example.bank.agent;

import java.util.List;

import com.example.bank.core.PromptSafety;

final class AgentSupport {

    private AgentSupport() {
    }

    static String normalizeIntent(String raw) {
        if (raw == null) {
            return "GENERAL";
        }
        return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "ACCOUNTS", "BALANCE", "ACCOUNT" -> "ACCOUNTS";
            case "TRANSACTIONS", "TRANSACTION", "HISTORY" -> "TRANSACTIONS";
            case "SPENDING", "EXPENSES", "EXPENDITURE" -> "SPENDING";
            case "INCOME", "SALARY", "EARNINGS" -> "INCOME";
            case "SEARCH" -> "SEARCH";
            case "CARDS", "CARD" -> "CARDS";
            case "PROFILE", "CUSTOMER" -> "PROFILE";
            case "KNOWLEDGE", "POLICY", "POLICIES", "PRODUCT", "FEE", "FEES", "RAG", "DOCUMENT" -> "KNOWLEDGE";
            default -> "GENERAL";
        };
    }

    static String priorResults(List<StepResult> results) {
        if (results == null || results.isEmpty()) {
            return "(none)";
        }
        StringBuilder builder = new StringBuilder();
        for (StepResult result : results) {
            builder.append("### ").append(result.agent()).append(": ").append(result.instruction()).append("\n")
                    .append(truncate(result.output(), 2500))
                    .append("\n\n");
        }
        // Step output can include tool results and model text from other agents.
        return PromptSafety.data(builder.toString().trim());
    }

    static String summarize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "(no output)";
        }
        String oneLine = value.replaceAll("\\s+", " ").trim();
        return truncate(oneLine, maxLength);
    }

    static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "... [truncated]";
    }
}
