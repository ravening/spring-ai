package com.example.stockagent.tools;

import com.example.stockagent.domain.Candle;
import com.example.stockagent.domain.PriceHistory;
import com.example.stockagent.domain.TechnicalResearch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministic technical analysis — no LLM involved in the numbers. */
public final class TechnicalIndicators {

    private TechnicalIndicators() {
    }

    public static TechnicalResearch analyse(PriceHistory history) {
        List<Candle> candles = history.candles();
        if (candles == null || candles.size() < 2) {
            return new TechnicalResearch(history.symbol(), null, null, null, null, null,
                    null, null, null, List.of(), List.of(),
                    "Not enough historical candles to compute technical levels.");
        }

        List<Double> closes = candles.stream().map(Candle::close).toList();
        double last = closes.get(closes.size() - 1);

        Double sma20 = sma(closes, 20);
        Double sma50 = sma(closes, 50);
        Double sma200 = sma(closes, 200);
        Double rsi = rsi(closes, 14);
        Double atr = atr(candles, 14);

        Candle latest = candles.get(candles.size() - 1);
        double pivot = (latest.high() + latest.low() + latest.close()) / 3.0;

        int window = Math.min(20, candles.size());
        List<Candle> recent = candles.subList(candles.size() - window, candles.size());
        double support = recent.stream().mapToDouble(Candle::low).min().orElse(latest.low());
        double resistance = recent.stream().mapToDouble(Candle::high).max().orElse(latest.high());

        List<Double> supportLevels = swingLevels(candles, true);
        List<Double> resistanceLevels = swingLevels(candles, false);

        StringBuilder interpretation = new StringBuilder();
        interpretation.append("Price ").append(fmt(last));
        if (sma20 != null) {
            interpretation.append(", ").append(last > sma20 ? "above" : "below")
                    .append(" SMA20 (").append(fmt(sma20)).append(")");
        }
        if (sma50 != null) {
            interpretation.append(", ").append(last > sma50 ? "above" : "below")
                    .append(" SMA50 (").append(fmt(sma50)).append(")");
        }
        if (rsi != null) {
            interpretation.append(". RSI14 ").append(fmt(rsi)).append(" (")
                    .append(rsi < 30 ? "oversold" : rsi > 70 ? "overbought" : "neutral").append(")");
        }
        interpretation.append(". Support ").append(fmt(support))
                .append(", resistance ").append(fmt(resistance)).append(".");

        return new TechnicalResearch(history.symbol(), sma20, sma50, sma200, rsi, atr,
                round(pivot), round(support), round(resistance),
                supportLevels, resistanceLevels, interpretation.toString());
    }

    public static Double sma(List<Double> values, int period) {
        if (values == null || values.size() < period) {
            return null;
        }
        double sum = 0;
        for (int i = values.size() - period; i < values.size(); i++) {
            sum += values.get(i);
        }
        return round(sum / period);
    }

    public static Double rsi(List<Double> closes, int period) {
        if (closes == null || closes.size() <= period) {
            return null;
        }
        double gain = 0;
        double loss = 0;
        for (int i = closes.size() - period; i < closes.size(); i++) {
            double change = closes.get(i) - closes.get(i - 1);
            if (change >= 0) {
                gain += change;
            } else {
                loss -= change;
            }
        }
        if (loss == 0) {
            return 100.0;
        }
        double rs = (gain / period) / (loss / period);
        return round(100 - (100 / (1 + rs)));
    }

    public static Double atr(List<Candle> candles, int period) {
        if (candles == null || candles.size() <= period) {
            return null;
        }
        double sum = 0;
        for (int i = candles.size() - period; i < candles.size(); i++) {
            Candle c = candles.get(i);
            double prevClose = candles.get(i - 1).close();
            sum += Math.max(c.high() - c.low(),
                    Math.max(Math.abs(c.high() - prevClose), Math.abs(c.low() - prevClose)));
        }
        return round(sum / period);
    }

    /** Last three swing lows (support) or swing highs (resistance), nearest first. */
    private static List<Double> swingLevels(List<Candle> candles, boolean lows) {
        int radius = 2;
        List<Double> levels = new ArrayList<>();
        for (int i = candles.size() - radius - 1; i >= radius; i--) {
            double value = lows ? candles.get(i).low() : candles.get(i).high();
            boolean isExtreme = true;
            for (int j = i - radius; j <= i + radius; j++) {
                if (j == i) continue;
                double other = lows ? candles.get(j).low() : candles.get(j).high();
                if (lows ? other < value : other > value) {
                    isExtreme = false;
                    break;
                }
            }
            if (isExtreme) {
                double rounded = round(value);
                if (levels.stream().noneMatch(l -> Math.abs(l - rounded) / rounded < 0.005)) {
                    levels.add(rounded);
                }
            }
            if (levels.size() >= 3) break;
        }
        levels.sort(Comparator.reverseOrder());
        return levels;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String fmt(Double value) {
        return value == null ? "n/a" : String.format("%.2f", value);
    }
}
