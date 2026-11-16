package com.example.agent.tools;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.agent.knowledge.KnowledgeService;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class LocalTools {

    private static final Map<String, String> TICKETS = tickets();

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private final KnowledgeService knowledgeService;

    public LocalTools(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Tool(name = "calculator", description = "Perform basic arithmetic on two numbers. Use this for every calculation instead of doing math yourself.")
    public String calculator(
            @ToolParam(description = "First operand", required = true) double a,
            @ToolParam(description = "Operation: add, subtract, multiply or divide", required = true) String operation,
            @ToolParam(description = "Second operand", required = true) double b) {

        String op = operation == null ? "" : operation.trim().toLowerCase();
        double result;
        switch (op) {
            case "add", "+" -> result = a + b;
            case "subtract", "-" -> result = a - b;
            case "multiply", "*", "x" -> result = a * b;
            case "divide", "/" -> {
                if (b == 0) {
                    return "Cannot divide by zero.";
                }
                result = a / b;
            }
            default -> {
                return "Unsupported operation '%s'. Use add, subtract, multiply or divide.".formatted(operation);
            }
        }
        return "%s %s %s = %s".formatted(format(a), op, format(b), format(result));
    }

    @Tool(name = "current_time", description = "Get the current date and time for an IANA time zone such as Europe/Berlin, UTC or Asia/Kolkata.")
    public String currentTime(
            @ToolParam(description = "IANA time zone id. Defaults to UTC.", required = false) String zoneId) {

        try {
            ZoneId zone = (zoneId == null || zoneId.isBlank()) ? ZoneId.of("UTC") : ZoneId.of(zoneId.trim());
            return TIME_FORMAT.format(ZonedDateTime.now(zone));
        }
        catch (Exception ex) {
            return "Unknown time zone '%s'. Use an IANA id such as Europe/Berlin or Asia/Kolkata.".formatted(zoneId);
        }
    }

    @Tool(name = "support_ticket_lookup", description = "Look up a support ticket by its id. Returns status, priority, owner and summary.")
    public String supportTicketLookup(
            @ToolParam(description = "Ticket id such as TCK-1001", required = true) String ticketId) {

        String key = ticketId == null ? "" : ticketId.trim().toUpperCase();
        String ticket = TICKETS.get(key);
        if (ticket == null) {
            return "Ticket %s was not found. Known ticket ids: %s".formatted(key, String.join(", ", TICKETS.keySet()));
        }
        return key + ": " + ticket;
    }

    @Tool(name = "support_ticket_search", description = "Search support tickets by keyword in the id, summary, status, priority or owner.")
    public String supportTicketSearch(
            @ToolParam(description = "Keyword to search for", required = true) String query) {

        String needle = query == null ? "" : query.trim().toLowerCase();
        if (needle.isEmpty()) {
            return "Provide a non-empty search keyword.";
        }
        StringBuilder result = new StringBuilder();
        TICKETS.forEach((id, ticket) -> {
            if ((id + " " + ticket).toLowerCase().contains(needle)) {
                result.append("- ").append(id).append(": ").append(ticket).append("\n");
            }
        });
        return result.isEmpty() ? "No tickets matched '" + query + "'." : result.toString().trim();
    }

    @Tool(name = "knowledge_search", description = "Search the internal knowledge base for policies, procedures and handbook topics. Use this for questions that local and MCP tools cannot answer.")
    public String knowledgeSearch(
            @ToolParam(description = "What to look for in the knowledge base", required = true) String query,
            @ToolParam(description = "Maximum number of passages (default 4)", required = false) Integer topK) {

        int limit = (topK == null || topK < 1) ? 4 : Math.min(topK, 12);
        List<Document> documents = knowledgeService.search(query, limit);
        if (documents.isEmpty()) {
            return "No knowledge-base passages matched '" + query + "'.";
        }
        StringBuilder result = new StringBuilder("Knowledge base passages:\n\n");
        for (Document document : documents) {
            Object source = document.getMetadata().get("source");
            result.append("Source: ").append(source == null ? "unknown" : source).append('\n');
            String text = document.getText() == null ? "" : document.getText();
            result.append(text.length() > 2000 ? text.substring(0, 2000) + "... [truncated]" : text)
                    .append("\n\n");
        }
        return result.toString().trim();
    }

    private static String format(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static Map<String, String> tickets() {
        Map<String, String> tickets = new LinkedHashMap<>();
        tickets.put("TCK-1001",
                "Login fails after password reset | status=OPEN | priority=HIGH | owner=platform-team | updated=2026-11-14");
        tickets.put("TCK-1002",
                "Invoice PDF is missing line items | status=IN_PROGRESS | priority=MEDIUM | owner=billing-team | updated=2026-11-13");
        tickets.put("TCK-1003",
                "MCP tool timeout in production | status=RESOLVED | priority=CRITICAL | owner=ai-platform | updated=2026-11-10");
        return Map.copyOf(tickets);
    }
}
