package com.example.stockagent.mcp;

import com.example.stockagent.domain.AnalysisEvent;
import com.example.stockagent.domain.StockReport;
import com.example.stockagent.service.AnalysisService;
import com.example.stockagent.service.MarketDataService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** Stock data and analysis exposed as MCP tools for external clients. */
@Component
public class StockMcpTools {

    private final MarketDataService marketData;
    private final AnalysisService analysisService;

    public StockMcpTools(MarketDataService marketData, AnalysisService analysisService) {
        this.marketData = marketData;
        this.analysisService = analysisService;
    }

    @McpTool(name = "stock_quote", description = "Get the latest price and daily range for a ticker")
    public String quote(@McpToolParam(description = "Stock ticker, e.g. AAPL") String symbol) {
        var quote = marketData.priceResearch(symbol.toUpperCase()).quote();
        return String.format("%s: %.2f (%+.2f%%) | day %.2f-%.2f | as of %d | source: %s",
                quote.symbol(), quote.currentPrice(), quote.changePercent(),
                quote.dayLow(), quote.dayHigh(), quote.asOfEpochSeconds(), quote.source());
    }

    @McpTool(name = "stock_technicals", description = "Compute support, resistance and indicators from historical candles")
    public String technicals(@McpToolParam(description = "Stock ticker") String symbol) {
        var history = marketData.priceResearch(symbol.toUpperCase()).history();
        var t = marketData.technical(symbol.toUpperCase(), history);
        return String.format("Support %.2f | Resistance %.2f | SMA20 %s | SMA50 %s | RSI14 %s | ATR14 %s. %s",
                t.support(), t.resistance(), t.sma20(), t.sma50(), t.rsi14(), t.atr14(), t.interpretation());
    }

    @McpTool(name = "stock_news", description = "Recent headlines and overall sentiment for a ticker")
    public String news(@McpToolParam(description = "Stock ticker") String symbol,
                       @McpToolParam(description = "Number of days to look back", required = false) Integer days) {
        var digest = marketData.newsResearch(symbol.toUpperCase());
        StringBuilder out = new StringBuilder("Sentiment: " + digest.sentiment() + "\n");
        digest.items().stream().limit(days == null ? 8 : days)
                .forEach(item -> out.append("- ").append(item.headline()).append('\n'));
        return out.toString();
    }

    @McpTool(name = "stock_analyze", description = "Run the full multi-agent stock analysis and return a Markdown report")
    public String analyze(@McpToolParam(description = "Stock ticker") String symbol) {
        List<AnalysisEvent> events = analysisService
                .analyse(symbol.toUpperCase(), "MCP analysis request", "mcp")
                .collectList()
                .block(Duration.ofSeconds(300));
        if (events == null) {
            return "Analysis timed out.";
        }
        return events.stream()
                .filter(e -> "report".equals(e.type()) && e.data() instanceof StockReport report)
                .map(e -> ((StockReport) e.data()).markdown())
                .findFirst()
                .orElse("Analysis produced no report.");
    }
}
