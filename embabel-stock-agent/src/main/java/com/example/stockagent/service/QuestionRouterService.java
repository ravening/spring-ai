package com.example.stockagent.service;

import com.example.stockagent.domain.ChatTurn;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Decides whether a message wants a full analysis or a specific factual answer,
 * and resolves the ticker (including follow-ups that omit it).
 */
@Service
public class QuestionRouterService {

    public record Route(String ticker, String intent) {
        public boolean hasTicker() {
            return ticker != null && !ticker.isBlank();
        }
    }

    private final ChatClient chatClient;
    private final TickerExtractorService tickerExtractor;

    public QuestionRouterService(ChatClient.Builder builder, TickerExtractorService tickerExtractor) {
        this.chatClient = builder.build();
        this.tickerExtractor = tickerExtractor;
    }

    public Route route(String message, List<ChatTurn> history) {
        try {
            Route route = chatClient.prompt()
                    .system("""
                            Classify the user's message for a stock assistant.
                            Reply with JSON only: {"ticker":"NVDA","intent":"EARNINGS"}

                            ticker: uppercase symbol, or NONE if no stock is meant.
                            intent must be exactly one of:
                              EARNINGS  - asks when earnings is, its time, results, EPS, quarterly report
                              DIVIDEND  - asks about dividend, ex-date, payout or yield
                              PRICE     - asks for the current price or quote only
                              NEWS      - asks for news, headlines or why a stock is moving
                              ANALYSIS  - asks for analysis, verdict, buy/sell/hold advice, or a general review

                            A follow-up such as "what time is its earnings?" inherits the ticker
                            from the previous conversation shown below. Never invent a ticker from
                            ordinary words: "whats going on?" has no ticker.

                            Previous conversation:
                            %s
                            """.formatted(historyText(history)))
                    .user(message)
                    .call()
                    .entity(Route.class);
            if (route != null) {
                String ticker = route.ticker() == null ? "" : route.ticker().trim().toUpperCase();
                if (ticker.equals("NONE")) ticker = "";
                ticker = ticker.replaceAll("[^A-Z0-9.\\-]", "");
                String intent = route.intent() == null ? "ANALYSIS" : route.intent().toUpperCase(Locale.ROOT);
                if (!List.of("EARNINGS", "DIVIDEND", "PRICE", "NEWS", "ANALYSIS").contains(intent)) {
                    intent = "ANALYSIS";
                }
                return new Route(ticker, intent);
            }
        } catch (Exception ignored) {
            // fall through to deterministic routing
        }
        String ticker = tickerExtractor.extract(message, history).orElse("");
        return new Route(ticker, guessIntent(message));
    }

    private String guessIntent(String message) {
        String text = message.toLowerCase(Locale.ROOT);
        if (text.matches(".*\\b(h|hi|hello|hey|thanks)\\b.*") && text.length() < 20) return "ANALYSIS";
        if (text.contains("earning") || text.contains("quarterly") || text.contains("eps")
                || text.contains("what time") || text.contains("report")) return "EARNINGS";
        if (text.contains("dividend") || text.contains("ex-date") || text.contains("payout")
                || text.contains("yield")) return "DIVIDEND";
        if (text.contains("news") || text.contains("headline") || text.contains("why"))
            return "NEWS";
        if (text.contains("price") || text.contains("quote") || text.contains("how much"))
            return "PRICE";
        return "ANALYSIS";
    }

    private String historyText(List<ChatTurn> history) {
        if (history == null || history.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, history.size() - 8);
        for (int i = from; i < history.size(); i++) {
            ChatTurn turn = history.get(i);
            String text = turn.content() == null ? "" : turn.content().replace("\n", " ");
            sb.append(turn.role()).append(": ").append(text, 0, Math.min(300, text.length())).append('\n');
        }
        return sb.toString();
    }
}
