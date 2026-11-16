package com.example.agent.agent;

import java.util.List;
import java.util.Locale;

final class AgentSupport {

    private AgentSupport() {
    }

    static String normalizeAgent(String raw) {
        if (raw == null) {
            return "RESEARCHER";
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "ANALYST", "ANALYSIS", "MATH", "CALCULATOR" -> "ANALYST";
            case "WRITER", "WRITING", "SYNTHESIS" -> "WRITER";
            default -> "RESEARCHER";
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
        return builder.toString().trim();
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
