package com.example.stockagent.domain;

import java.util.List;

/** Deterministic technical analysis result. */
public record TechnicalResearch(
        String symbol,
        Double sma20,
        Double sma50,
        Double sma200,
        Double rsi14,
        Double atr14,
        Double pivot,
        Double support,
        Double resistance,
        List<Double> supportLevels,
        List<Double> resistanceLevels,
        String interpretation
) {
}
