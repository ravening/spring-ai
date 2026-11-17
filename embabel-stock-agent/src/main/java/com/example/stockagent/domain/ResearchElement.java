package com.example.stockagent.domain;

/**
 * One element produced by a parallel research branch. Exactly one of the four
 * payloads is normally populated; the consolidator merges them into a bundle.
 */
public record ResearchElement(
        String kind,
        PriceResearch price,
        NewsResearch news,
        FundamentalResearch fundamentals,
        TechnicalResearch technicals,
        String error
) {
    public static ResearchElement price(PriceResearch value) {
        return new ResearchElement("price", value, null, null, null, null);
    }

    public static ResearchElement news(NewsResearch value) {
        return new ResearchElement("news", null, value, null, null, null);
    }

    public static ResearchElement fundamentals(FundamentalResearch value) {
        return new ResearchElement("fundamentals", null, null, value, null, null);
    }

    public static ResearchElement technicals(TechnicalResearch value) {
        return new ResearchElement("technicals", null, null, null, value, null);
    }

    public static ResearchElement failed(String kind, String message) {
        return new ResearchElement(kind, null, null, null, null, message);
    }
}
