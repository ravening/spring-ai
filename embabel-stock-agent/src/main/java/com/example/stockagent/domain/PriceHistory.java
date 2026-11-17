package com.example.stockagent.domain;

import java.util.List;

/** Historical OHLCV candles used to compute technical levels. */
public record PriceHistory(
        String symbol,
        String interval,
        List<Candle> candles,
        long asOfEpochSeconds,
        String source
) {
}
