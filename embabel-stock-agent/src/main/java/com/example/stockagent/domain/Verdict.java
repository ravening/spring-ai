package com.example.stockagent.domain;

import java.util.List;

/** Actionable verdict produced from the combined research. */
public record Verdict(
        String symbol,
        String action,
        double confidence,
        String entryZone,
        String stopLoss,
        String takeProfit,
        String timeHorizon,
        String rationale,
        List<String> keyReasons,
        List<String> risks
) {
}
