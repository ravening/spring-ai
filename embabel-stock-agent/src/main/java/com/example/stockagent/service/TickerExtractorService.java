package com.example.stockagent.service;

import com.example.stockagent.domain.ChatTurn;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns free-form user text into a ticker, using conversation history for follow-ups. */
@Service
public class TickerExtractorService {

    public record Extraction(String ticker, boolean needsClarification) {
    }

    private static final Pattern TICKER = Pattern.compile("\\$?\\b[A-Za-z][A-Za-z0-9.\\-]{0,9}\\b");
    private static final Pattern REPORT_SYMBOL = Pattern.compile("#\\s*([A-Z][A-Z0-9.\\-]{0,9})\\s+[—-]");
    private static final Pattern GREETING = Pattern.compile(
            "(?i)^\\s*(hi|hello|hey|thanks|thank you|ok|okay|got it|good morning)[\\s.!?]*$");
    private static final List<String> STOP_WORDS = List.of(
            "analyse", "analyzing", "analyze", "analysis", "what", "about", "price", "prices",
            "stock", "stocks", "should", "buy", "sell", "hold", "wait", "please", "give", "tell",
            "the", "for", "is", "of", "hello", "hi", "hey", "how", "are", "you", "i", "me", "my",
            "can", "could", "would", "do", "does", "did", "it", "this", "that", "why", "when",
            "where", "who", "explain", "news", "market", "company", "earnings", "dividend", "now",
            "today", "good", "morning", "thanks", "thank", "help", "going", "on", "with", "and",
            "there", "whats", "update", "more", "latest", "recent");

    private final ChatClient chatClient;

    public TickerExtractorService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public Optional<String> extract(String text, List<ChatTurn> history) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            Extraction result = chatClient.prompt()
                    .system("""
                            Extract the stock ticker the user is asking about.
                            Reply with ONLY the ticker symbol (uppercase) or the single word NONE.

                            Rules:
                            - If the message contains an explicit ticker, return it (e.g. "analyse ARM" -> ARM).
                            - If it is a follow-up with no ticker (e.g. "what's going on?", "any update?"),
                              return the ticker from the previous conversation shown below.
                            - Never invent a ticker from ordinary words. "whats going on?" is NOT a ticker.
                            - If there is no ticker anywhere, reply NONE.

                            Previous conversation:
                            %s
                            """.formatted(historyText(history)))
                    .user(text)
                    .call()
                    .entity(Extraction.class);
            if (result != null && result.ticker() != null && !result.ticker().isBlank()
                    && !result.ticker().equalsIgnoreCase("NONE")) {
                return Optional.of(normalize(result.ticker()));
            }
        } catch (Exception ignored) {
            // fall through to deterministic resolution
        }
        Optional<String> direct = fallback(text);
        return direct.isPresent() ? direct : fromHistory(text, history);
    }

    private Optional<String> fallback(String text) {
        // An explicit $TICKER is always a ticker.
        Matcher explicit = Pattern.compile("\\$([A-Za-z][A-Za-z0-9.\\-]{0,9})").matcher(text);
        if (explicit.find()) {
            return Optional.of(explicit.group(1).toUpperCase());
        }
        Matcher matcher = TICKER.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group().toUpperCase();
            if (!STOP_WORDS.contains(candidate.toLowerCase())
                    && candidate.length() >= 2
                    && candidate.matches("[A-Z0-9.\\-]{1,6}")) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Follow-up messages inherit the ticker of the most recent analysis in this chat. */
    private Optional<String> fromHistory(String text, List<ChatTurn> history) {
        if (history == null || history.isEmpty() || GREETING.matcher(text).matches()) {
            return Optional.empty();
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            String content = history.get(i).content();
            if (content == null) continue;
            Matcher report = REPORT_SYMBOL.matcher(content);
            if (report.find()) {
                return Optional.of(report.group(1));
            }
        }
        return Optional.empty();
    }

    private String historyText(List<ChatTurn> history) {
        if (history == null || history.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, history.size() - 8);
        for (int i = from; i < history.size(); i++) {
            ChatTurn turn = history.get(i);
            String text = turn.content() == null ? "" : turn.content().replace("\n", " ");
            sb.append(turn.role()).append(": ")
                    .append(text, 0, Math.min(300, text.length())).append('\n');
        }
        return sb.toString();
    }

    private String normalize(String value) {
        return value.trim().toUpperCase().replaceAll("[^A-Z0-9.\\-]", "");
    }
}
