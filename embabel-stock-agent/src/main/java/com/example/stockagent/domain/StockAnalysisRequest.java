package com.example.stockagent.domain;

/** Input to the stock analysis agent process. */
public record StockAnalysisRequest(
        String ticker,
        String conversationId,
        String question
) {
    public StockAnalysisRequest withTicker(String newTicker) {
        return new StockAnalysisRequest(newTicker, conversationId, question);
    }
}
