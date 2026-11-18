package com.example.bank.tools;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.example.bank.core.PromptSafety;
import com.example.bank.knowledge.KnowledgeService;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * App-local tools. Banking data is deliberately NOT here: it only comes from the
 * bank MCP server. These tools cover RAG retrieval, arithmetic and time.
 */
@Component
public class LocalTools {

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

    @Tool(name = "knowledge_search", description = "Search the bank knowledge base (bank policies, products, fees) and documents uploaded by the user for this conversation or for all their conversations. Use this for questions about bank products, fees, policies, security or anything mentioned in uploaded files. The conversationId and userId are injected automatically.")
    public String knowledgeSearch(
            @ToolParam(description = "What to look for in the knowledge base", required = true) String query,
            @ToolParam(description = "Maximum number of passages (default 4)", required = false) Integer topK,
            @ToolParam(description = "Injected from the tool context; do not set manually.", required = false) String conversationId,
            @ToolParam(description = "Injected from the tool context; do not set manually.", required = false) String userId) {

        int limit = (topK == null || topK < 1) ? 4 : Math.min(topK, 12);
        List<Document> documents = knowledgeService.search(query, limit, conversationId, userId);
        if (documents.isEmpty()) {
            return "No knowledge-base passages matched '" + query + "'.";
        }
        StringBuilder result = new StringBuilder();
        for (Document document : documents) {
            Object source = document.getMetadata().get("source");
            result.append("Source: ").append(source == null ? "unknown" : source).append('\n');
            String text = document.getText() == null ? "" : document.getText();
            result.append(text.length() > 2000 ? text.substring(0, 2000) + "... [truncated]" : text)
                    .append("\n\n");
        }
        // Retrieved text is untrusted data, never instructions to the model.
        return PromptSafety.data("Knowledge base passages:\n" + result.toString().trim());
    }

    private static String format(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
