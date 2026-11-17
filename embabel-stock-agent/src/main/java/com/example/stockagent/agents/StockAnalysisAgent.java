package com.example.stockagent.agents;

import com.example.stockagent.domain.AnalysisEvent;
import com.example.stockagent.domain.FundamentalResearch;
import com.example.stockagent.domain.NewsResearch;
import com.example.stockagent.domain.PriceResearch;
import com.example.stockagent.domain.ResearchBundle;
import com.example.stockagent.domain.ResearchElement;
import com.example.stockagent.domain.StockReport;
import com.example.stockagent.domain.StreamingAnalysisRequest;
import com.example.stockagent.domain.TechnicalResearch;
import com.example.stockagent.domain.TickerResolution;
import com.example.stockagent.domain.Verdict;
import com.example.stockagent.service.MarketDataService;
import com.example.stockagent.service.ReportFormatter;
import com.example.stockagent.service.VerdictService;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.workflow.control.ScatterGatherBuilder;
import com.embabel.agent.api.streaming.StreamingPromptRunnerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Goal-driven stock analysis. Four research branches run in parallel via
 * Embabel's ScatterGatherBuilder, then a verdict and a streamed Markdown report
 * are produced from the combined typed results.
 */
@Agent(
        name = "stock-analysis-agent",
        description = "Researches a stock ticker in parallel (price, news, fundamentals and technicals), "
                + "then produces a verdict and a Markdown report from live data"
)
@Component
public class StockAnalysisAgent {

    private static final Logger log = LoggerFactory.getLogger(StockAnalysisAgent.class);

    private final MarketDataService marketData;
    private final VerdictService verdictService;

    public StockAnalysisAgent(MarketDataService marketData, VerdictService verdictService) {
        this.marketData = marketData;
        this.verdictService = verdictService;
    }

    @Action(description = "Resolve and normalise the ticker symbol supplied by the user")
    public TickerResolution resolveTicker(StreamingAnalysisRequest request, ActionContext context) {
        String ticker = request.base().ticker();
        if (ticker == null || ticker.isBlank()) {
            request.emit(AnalysisEvent.error("ticker", "No ticker symbol was provided."));
            return new TickerResolution("", "No ticker symbol was provided.");
        }
        String normalised = ticker.trim().toUpperCase().replaceAll("[^A-Z0-9.\\-]", "");
        request.emit(AnalysisEvent.stage("ticker", "Analysing " + normalised));
        return new TickerResolution(normalised, "Ticker supplied by the user");
    }

    @AchievesGoal(description = "A complete stock analysis report with verdict, technicals, news and calendar events")
    @Action(description = "Run parallel research, consolidate it and write the streamed report")
    public StockReport analyse(TickerResolution resolution,
                               StreamingAnalysisRequest request,
                               ActionContext context) {
        String symbol = resolution.ticker();
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("A ticker symbol is required");
        }

        ResearchBundle bundle = ScatterGatherBuilder.returning(ResearchBundle.class)
                .fromElements(ResearchElement.class)
                .withGenerators(List.of(
                        ctx -> price(symbol, request),
                        ctx -> news(symbol, request),
                        ctx -> fundamentals(symbol, request),
                        ctx -> technicals(symbol, request)
                ))
                .consolidatedBy(ctx -> consolidate(symbol, ctx.getInput().getResults()))
                .asSubProcess(context);

        request.emit(AnalysisEvent.stage("verdict", "Weighing the evidence"));
        Verdict verdict = verdictService.verdict(bundle, context, request.context());

        StockReport deterministic = new StockReport(
                symbol,
                bundle.price().quote(),
                bundle.technicals(),
                bundle.news(),
                bundle.fundamentals(),
                verdict,
                "",
                Instant.now().getEpochSecond()
        );
        String fallbackMarkdown = ReportFormatter.format(deterministic);
        String markdown = streamReport(request, context, fallbackMarkdown);

        StockReport report = new StockReport(
                symbol,
                bundle.price().quote(),
                bundle.technicals(),
                bundle.news(),
                bundle.fundamentals(),
                verdict,
                markdown,
                Instant.now().getEpochSecond()
        );
        request.emit(AnalysisEvent.report("report", report));
        // "done" is emitted by AnalysisService only after the turn is persisted,
        // so clients never refresh the conversation list before the save commits.
        return report;
    }

    private ResearchElement price(String symbol, StreamingAnalysisRequest request) {
        request.emit(AnalysisEvent.stage("price", "Fetching live quote and history"));
        try {
            PriceResearch research = marketData.priceResearch(symbol);
            request.emit(AnalysisEvent.tool("price", "Finnhub quote + Yahoo chart for " + symbol));
            return ResearchElement.price(research);
        } catch (Exception e) {
            return ResearchElement.failed("price", e.getMessage());
        }
    }

    private ResearchElement news(String symbol, StreamingAnalysisRequest request) {
        request.emit(AnalysisEvent.stage("news", "Scanning recent news"));
        try {
            NewsResearch research = marketData.newsResearch(symbol);
            request.emit(AnalysisEvent.tool("news", "Finnhub news for " + symbol));
            return ResearchElement.news(research);
        } catch (Exception e) {
            return ResearchElement.failed("news", e.getMessage());
        }
    }

    private ResearchElement fundamentals(String symbol, StreamingAnalysisRequest request) {
        request.emit(AnalysisEvent.stage("fundamentals", "Checking earnings and dividends"));
        try {
            FundamentalResearch research = marketData.fundamentals(symbol);
            request.emit(AnalysisEvent.tool("fundamentals", "Finnhub earnings calendar + dividends for " + symbol));
            return ResearchElement.fundamentals(research);
        } catch (Exception e) {
            return ResearchElement.failed("fundamentals", e.getMessage());
        }
    }

    private ResearchElement technicals(String symbol, StreamingAnalysisRequest request) {
        request.emit(AnalysisEvent.stage("technicals", "Computing support and resistance"));
        try {
            TechnicalResearch research = marketData.technical(symbol, marketData.history(symbol));
            request.emit(AnalysisEvent.tool("technicals", "SMA / RSI / ATR / swing levels"));
            return ResearchElement.technicals(research);
        } catch (Exception e) {
            return ResearchElement.failed("technicals", e.getMessage());
        }
    }

    private ResearchBundle consolidate(String symbol, List<ResearchElement> elements) {
        PriceResearch price = null;
        NewsResearch news = null;
        FundamentalResearch fundamentals = null;
        TechnicalResearch technicals = null;
        List<String> errors = new ArrayList<>();
        for (ResearchElement element : elements) {
            if (element.error() != null) {
                errors.add(element.kind() + ": " + element.error());
            }
            if (element.price() != null) price = element.price();
            if (element.news() != null) news = element.news();
            if (element.fundamentals() != null) fundamentals = element.fundamentals();
            if (element.technicals() != null) technicals = element.technicals();
        }
        if (price == null) {
            price = marketData.priceResearch(symbol);
        }
        if (news == null) news = marketData.newsResearch(symbol);
        if (fundamentals == null) fundamentals = marketData.fundamentals(symbol);
        if (technicals == null) technicals = marketData.technical(symbol, price.history());
        return new ResearchBundle(symbol, price, news, fundamentals, technicals, errors);
    }

    /** Streams Markdown tokens through the SSE sink; Embabel's streaming API does the inference. */
    private String streamReport(StreamingAnalysisRequest request, ActionContext context, String fallbackMarkdown) {
        request.emit(AnalysisEvent.stage("report", "Writing the report"));
        StringBuilder previous = new StringBuilder();
        var turns = request.context().history();
        int from = Math.max(0, turns.size() - 6);
        for (int i = from; i < turns.size(); i++) {
            var turn = turns.get(i);
            String text = turn.content() == null ? "" : turn.content();
            previous.append(turn.role()).append(": ")
                    .append(text.replace("\n", " "), 0, Math.min(400, text.length())).append('\n');
        }
        String prompt = """
                Rewrite the following stock analysis as a polished Markdown report.
                Keep every number exactly as given, keep the table structure, use **bold** for key figures,
                use headings and bullet lists, and do not add facts that are not present.
                Keep it concise. The "Previous conversation" is context: if the user asked a follow-up
                question (for example "what's going on?"), make the report answer it using the fresh data.

                Previous conversation:
                %s

                Analysis data:
                %s
                """.formatted(previous.isEmpty() ? "(none)" : previous.toString(), fallbackMarkdown);

        StringBuilder collected = new StringBuilder();
        try {
            new StreamingPromptRunnerBuilder(context.ai().withDefaultLlm())
                    .streaming()
                    .withPrompt(prompt)
                    .generateStream()
                    .timeout(Duration.ofSeconds(180))
                    .doOnNext(chunk -> {
                        collected.append(chunk);
                        request.emit(AnalysisEvent.token("report", chunk));
                    })
                    .blockLast(Duration.ofSeconds(200));
        } catch (Exception e) {
            log.warn("Streaming report generation failed: {}", e.getMessage());
        }
        if (collected.isEmpty()) {
            for (int i = 0; i < fallbackMarkdown.length(); i += 60) {
                String chunk = fallbackMarkdown.substring(i, Math.min(i + 60, fallbackMarkdown.length()));
                request.emit(AnalysisEvent.token("report", chunk));
            }
            return fallbackMarkdown;
        }
        return collected.toString();
    }
}
