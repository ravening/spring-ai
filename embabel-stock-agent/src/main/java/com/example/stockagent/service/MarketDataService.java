package com.example.stockagent.service;

import com.example.stockagent.domain.FundamentalResearch;
import com.example.stockagent.domain.NewsItem;
import com.example.stockagent.domain.NewsResearch;
import com.example.stockagent.domain.PriceHistory;
import com.example.stockagent.domain.PriceQuote;
import com.example.stockagent.domain.PriceResearch;
import com.example.stockagent.domain.TechnicalResearch;
import com.example.stockagent.tools.FinnhubClient;
import com.example.stockagent.tools.TechnicalIndicators;
import com.example.stockagent.tools.YahooChartClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** Aggregates the two market-data providers into typed research results. */
@Service
public class MarketDataService {

    private static final List<String> POSITIVE = List.of("up", "gain", "rise", "beats", "growth", "surge",
            "record", "strong", "upgrade", "profit", "rally", "bullish", "approve", "wins");
    private static final List<String> NEGATIVE = List.of("down", "loss", "fall", "miss", "drop", "slump",
            "cut", "downgrade", "weak", "bearish", "fraud", "probe", "lawsuit", "risk", "warn");

    private final FinnhubClient finnhub;
    private final YahooChartClient yahoo;

    public MarketDataService(FinnhubClient finnhub, YahooChartClient yahoo) {
        this.finnhub = finnhub;
        this.yahoo = yahoo;
    }

    public PriceResearch priceResearch(String symbol) {
        PriceQuote quote = finnhub.quote(symbol);
        PriceHistory history = yahoo.daily(symbol);
        String note = "";

        if (quote.currentPrice() == 0 && !history.candles().isEmpty()) {
            double last = history.candles().get(history.candles().size() - 1).close();
            double prev = history.candles().size() > 1
                    ? history.candles().get(history.candles().size() - 2).close() : last;
            quote = new PriceQuote(symbol, last, last - prev,
                    prev == 0 ? 0 : (last - prev) / prev * 100,
                    last, last, last, prev, history.asOfEpochSeconds(),
                    "Yahoo Finance (Finnhub quote unavailable - delayed)");
            note = "Live Finnhub quote unavailable; using latest Yahoo close (delayed).";
        }
        if (!finnhub.configured()) {
            note = "FINNHUB_API_KEY is not set — live quote/news/events are unavailable. "
                    + "Price shown comes from keyless Yahoo Finance and is delayed.";
        }
        return new PriceResearch(quote, history, note);
    }

    public NewsResearch newsResearch(String symbol) {
        List<NewsItem> items = finnhub.news(symbol, 14);
        String sentiment = sentiment(items);
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < Math.min(4, items.size()); i++) {
            summary.append("- ").append(items.get(i).headline()).append('\n');
        }
        if (summary.isEmpty()) {
            summary.append("No recent headlines available from Finnhub.");
        }
        return new NewsResearch(symbol, items, sentiment, summary.toString(), Instant.now().getEpochSecond());
    }

    public FundamentalResearch fundamentals(String symbol) {
        return finnhub.fundamentals(symbol);
    }

    public PriceHistory history(String symbol) {
        return yahoo.daily(symbol);
    }

    public TechnicalResearch technical(String symbol, PriceHistory history) {
        return TechnicalIndicators.analyse(history);
    }

    private String sentiment(List<NewsItem> items) {
        int positive = 0;
        int negative = 0;
        for (NewsItem item : items) {
            String text = (item.headline() + " " + item.summary()).toLowerCase();
            for (String word : POSITIVE) if (text.contains(word)) positive++;
            for (String word : NEGATIVE) if (text.contains(word)) negative++;
        }
        if (positive == 0 && negative == 0) return "NEUTRAL";
        if (positive > negative * 1.5) return "POSITIVE";
        if (negative > positive * 1.5) return "NEGATIVE";
        return "MIXED";
    }
}
