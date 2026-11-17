package com.example.stockagent.service;

import com.example.stockagent.domain.FundamentalResearch;
import com.example.stockagent.domain.NewsResearch;
import com.example.stockagent.domain.PriceQuote;
import com.example.stockagent.domain.StockReport;
import com.example.stockagent.domain.TechnicalResearch;
import com.example.stockagent.domain.Verdict;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Deterministic Markdown fallback; also used to build the stored report. */
public final class ReportFormatter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneId.of("UTC"));

    private ReportFormatter() {
    }

    public static String format(StockReport report) {
        PriceQuote q = report.quote();
        TechnicalResearch t = report.technicals();
        NewsResearch n = report.news();
        FundamentalResearch f = report.fundamentals();
        Verdict v = report.verdict();

        StringBuilder md = new StringBuilder();
        md.append("# ").append(report.symbol()).append(" — stock analysis\n\n");
        md.append("**Verdict:** ").append(v.action()).append("  ")
                .append("**Confidence:** ").append(String.format("%.0f%%", v.confidence() * 100)).append("\n\n");
        md.append("> Data as of ").append(DATE.format(Instant.ofEpochSecond(report.generatedAtEpochSeconds())))
                .append(". Sources: ").append(q.source()).append("; Yahoo Finance chart (delayed) for history.\n\n");

        md.append("## Price\n\n");
        md.append("| Metric | Value |\n|---|---:|\n");
        md.append("| Last price | ").append(fmt(q.currentPrice())).append(" |\n");
        md.append("| Change | ").append(fmt(q.change())).append(" (").append(fmt(q.changePercent())).append("%) |\n");
        md.append("| Day range | ").append(fmt(q.dayLow())).append(" – ").append(fmt(q.dayHigh())).append(" |\n");
        md.append("| Previous close | ").append(fmt(q.previousClose())).append(" |\n\n");

        md.append("## Technical levels\n\n");
        md.append("| Indicator | Value |\n|---|---:|\n");
        md.append("| SMA 20 / 50 / 200 | ").append(fmt(t.sma20())).append(" / ")
                .append(fmt(t.sma50())).append(" / ").append(fmt(t.sma200())).append(" |\n");
        md.append("| RSI (14) | ").append(fmt(t.rsi14())).append(" |\n");
        md.append("| ATR (14) | ").append(fmt(t.atr14())).append(" |\n");
        md.append("| Pivot | ").append(fmt(t.pivot())).append(" |\n");
        md.append("| **Support** | **").append(fmt(t.support())).append("** |\n");
        md.append("| **Resistance** | **").append(fmt(t.resistance())).append("** |\n\n");
        md.append("Support zones: ").append(join(t.supportLevels())).append(".  \n");
        md.append("Resistance zones: ").append(join(t.resistanceLevels())).append(".\n\n");
        md.append(t.interpretation()).append("\n\n");

        md.append("## Trade plan\n\n");
        md.append("- **Entry zone:** ").append(v.entryZone()).append("\n");
        md.append("- **Stop loss:** ").append(v.stopLoss()).append("\n");
        md.append("- **Take profit:** ").append(v.takeProfit()).append("\n");
        md.append("- **Horizon:** ").append(v.timeHorizon()).append("\n\n");
        md.append(v.rationale()).append("\n\n");

        if (!v.keyReasons().isEmpty()) {
            md.append("### Why\n\n");
            v.keyReasons().forEach(reason -> md.append("- ").append(reason).append("\n"));
            md.append("\n");
        }
        if (!v.risks().isEmpty()) {
            md.append("### Risks\n\n");
            v.risks().forEach(risk -> md.append("- ").append(risk).append("\n"));
            md.append("\n");
        }

        md.append("## News sentiment: ").append(n.sentiment()).append("\n\n");
        for (var item : n.items().stream().limit(6).toList()) {
            md.append("- ").append(item.headline()).append(" _(").append(item.source()).append(")_\n");
        }
        md.append("\n");

        md.append("## Calendar\n\n");
        md.append("- **Next earnings:** ").append(value(f.nextEarningsDate()))
                .append(" (").append(value(f.earningsQuarter())).append(", ")
                .append(value(f.earningsHour()).equals("not available")
                        ? "session not available" : "session " + f.earningsHour().toUpperCase())
                .append(", EPS estimate: ").append(value(f.earningsPerShareEstimate())).append(")\n");
        md.append("- **Dividend:** ").append(value(f.dividendPerShare()))
                .append(" (ex-date: ").append(value(f.exDividendDate())).append(")\n\n");

        md.append("---\n\n_This is informational analysis, not financial advice._\n");
        return md.toString();
    }

    private static String fmt(Double value) {
        return value == null ? "n/a" : String.format("%.2f", value);
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "not available" : value;
    }

    private static String join(List<Double> values) {
        if (values == null || values.isEmpty()) return "n/a";
        return values.stream().map(ReportFormatter::fmt).reduce((a, b) -> a + ", " + b).orElse("n/a");
    }
}
